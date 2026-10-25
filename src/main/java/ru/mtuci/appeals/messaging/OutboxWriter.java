package ru.mtuci.appeals.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Запись доменного события в outbox (решение 3): в той же транзакции, что и изменение
 * обращения: либо сохраняются оба, либо ни одно. Без транзакции событие записать нельзя:
 * MANDATORY превращает такую ошибку разработки в исключение, а не в тихо потерянное событие.
 * В RabbitMQ событие доставляет {@link OutboxRelay}.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AppealDomainEvent event) {
        jdbc.sql("""
                        insert into outbox_event (id, appeal_id, event_type, routing_key, details, occurred_at)
                        values (:id, :appealId, :eventType, :routingKey, cast(:details as jsonb), :occurredAt)""")
                .param("id", UUID.randomUUID())
                .param("appealId", event.appealId())
                .param("eventType", event.eventType())
                .param("routingKey", event.routingKey())
                .param("details", toJson(event))
                .param("occurredAt", Timestamp.from(Instant.now()))
                .update();
    }

    private String toJson(AppealDomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event.details());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось записать атрибуты события " + event.eventType(), exception);
        }
    }
}
