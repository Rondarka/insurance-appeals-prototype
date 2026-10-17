package ru.mtuci.appeals.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Доставка событий из outbox в RabbitMQ (решение 3).
 *
 * Проход берёт неотправленные события по порядку через SELECT … FOR UPDATE SKIP LOCKED:
 * второй экземпляр подсистемы пропустит строки, которые уже отправляет первый, и не разошлёт
 * их дважды. Каждое событие отправляется с подтверждением брокера, и только после него
 * помечается отправленным. Брокер недоступен — проход останавливается, а оставшиеся события
 * ждут следующего: так сохраняется порядок событий одного обращения.
 *
 * Гарантия — at-least-once: если подсистема упадёт между подтверждением брокера и отметкой
 * в базе, событие уйдёт ещё раз. Повтор отсекают потребители по eventId (решение 4).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_MILLIS = 5000;

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final Duration retention;

    public OutboxRelay(JdbcClient jdbc,
                       RabbitTemplate rabbitTemplate,
                       ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager,
                       @Value("${appeals.outbox.batch-size}") int batchSize,
                       @Value("${appeals.outbox.retention}") Duration retention) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.retention = retention;
    }

    @Scheduled(initialDelayString = "${appeals.outbox.initial-delay}", fixedDelayString = "${appeals.outbox.interval}")
    public void scheduledRelay() {
        relay();
    }

    /** Один проход; возвращает, сколько событий отправлено. */
    public int relay() {
        Integer sent = transactions.execute(status -> {
            List<Row> batch = jdbc.sql("""
                            select id, appeal_id, event_type, routing_key, details::text as details, occurred_at
                            from outbox_event
                            where published_at is null
                            order by occurred_at
                            limit :limit
                            for update skip locked""")
                    .param("limit", batchSize)
                    .query(Row.class)
                    .list();
            int count = 0;
            for (Row row : batch) {
                try {
                    send(row);
                } catch (AmqpException exception) {
                    jdbc.sql("update outbox_event set attempts = attempts + 1, last_error = :error where id = :id")
                            .param("error", abbreviate(exception.getMessage()))
                            .param("id", row.id())
                            .update();
                    log.warn("RabbitMQ недоступен, событие {} ждёт следующего прохода: {}", row.id(), exception.getMessage());
                    break;
                }
                jdbc.sql("update outbox_event set published_at = :now, attempts = attempts + 1, last_error = null where id = :id")
                        .param("now", Timestamp.from(Instant.now()))
                        .param("id", row.id())
                        .update();
                count++;
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    /** Отправленные события больше не нужны: журнал аудита хранит их отдельно. */
    @Scheduled(initialDelayString = "${appeals.outbox.cleanup-interval}", fixedDelayString = "${appeals.outbox.cleanup-interval}")
    public void cleanup() {
        int removed = jdbc.sql("delete from outbox_event where published_at < :border")
                .param("border", Timestamp.from(Instant.now().minus(retention)))
                .update();
        if (removed > 0) {
            log.info("Из outbox удалено отправленных событий: {}", removed);
        }
    }

    /** Сколько событий ждут отправки и с какого момента — для администрирования. */
    public Backlog backlog() {
        return jdbc.sql("select count(*) as pending, min(occurred_at) as oldest from outbox_event where published_at is null")
                .query((rs, rowNum) -> new Backlog(rs.getLong("pending"),
                        rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()))
                .single();
    }

    private void send(Row row) {
        AppealEvent event = new AppealEvent(row.id(), row.eventType(), row.appealId(), row.routingKey(),
                readDetails(row.details()), row.occurredAt().toInstant());
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(RabbitTopology.APPEAL_EXCHANGE, event.routingKey(), event);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
            return null;
        });
        log.info("Published {} for appeal {} with routing key {}", event.eventType(), event.appealId(), event.routingKey());
    }

    private Map<String, String> readDetails(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать атрибуты события", exception);
        }
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    record Row(UUID id, UUID appealId, String eventType, String routingKey, String details, Timestamp occurredAt) {
    }

    public record Backlog(long pending, Instant oldest) {
    }
}
