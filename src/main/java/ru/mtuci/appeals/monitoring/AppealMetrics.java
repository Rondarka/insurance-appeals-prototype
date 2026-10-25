package ru.mtuci.appeals.monitoring;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.ContractLink;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.messaging.OutboxRelay;
import ru.mtuci.appeals.repository.AppealRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Показатели обращений для Prometheus (решение 47). Слушает те же доменные события, что и
 * outbox, но только после фиксации транзакции: откатившееся действие в метрики не попадает.
 * Технические метрики (время ответа API, JVM, пул соединений, RabbitMQ) Spring Boot снимает сам.
 */
@Component
public class AppealMetrics {

    private static final Logger log = LoggerFactory.getLogger(AppealMetrics.class);

    private final MeterRegistry registry;
    private final AppealRepository appeals;
    private final ZoneId zone;

    public AppealMetrics(MeterRegistry registry, AppealRepository appeals, OutboxRelay outbox, ContractsClient contracts,
                         JdbcClient jdbc, @Value("${appeals.timezone}") String timezone) {
        this.registry = registry;
        this.appeals = appeals;
        this.zone = ZoneId.of(timezone);
        // состояние считается в момент опроса Prometheus, отдельный планировщик не нужен
        Gauge.builder("appeals.outbox.pending", outbox, relay -> relay.backlog().pending())
                .description("Событий в outbox ждут отправки в брокер")
                .register(registry);
        Gauge.builder("appeals.outbox.oldest.age", outbox, relay -> secondsSince(relay.backlog().oldest()))
                .description("Сколько секунд ждёт самое старое неотправленное событие")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("appeals.contracts.circuit.state", contracts, client -> stateCode(client.circuitBreaker().getState()))
                .description("Цепь учётной системы договоров: 0 замкнута, 1 разомкнута, 2 пробные вызовы")
                .register(registry);
        Gauge.builder("appeals.open.legal.overdue", jdbc, db -> db.sql("""
                                select count(*) from appeal
                                where status not in ('RESOLVED', 'CLOSED') and legal_deadline < :today""")
                        .param("today", LocalDate.now(zone))
                        .query(Long.class)
                        .single())
                .description("Открытых обращений, у которых нарушен срок по закону")
                .register(registry);
    }

    /** Ошибка подсчёта не должна ломать обработку обращения: только запись в журнал. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AppealDomainEvent event) {
        try {
            switch (event.eventType()) {
                case "APPEAL_CREATED" -> registered(event);
                case "APPEAL_ROUTED" -> routed(event);
                case "APPEAL_STATUS_CHANGED" -> {
                    if ("RESOLVED".equals(event.details().get("toStatus"))) {
                        resolved(event);
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Метрика по событию {} обращения {} не учтена: {}", event.eventType(), event.appealId(),
                    exception.getMessage());
        }
    }

    /** Поступление: канал, категория и принято ли без договора (резервный режим, решение 29). */
    private void registered(AppealDomainEvent event) {
        appeals.findById(event.appealId()).ifPresent(appeal -> Counter.builder("appeals.registered")
                .description("Зарегистрировано обращений")
                .tag("channel", appeal.getChannel().name())
                .tag("category", appeal.getCategory())
                .tag("contract", appeal.getContractLink() == ContractLink.LINKED ? "linked" : "pending")
                .register(registry)
                .increment());
    }

    /** Задержка маршрутизации: от регистрации до назначения отдела, автоматически или руководителем. */
    private void routed(AppealDomainEvent event) {
        appeals.findById(event.appealId()).ifPresent(appeal -> Timer.builder("appeals.routing.lag")
                .description("От регистрации обращения до назначения отдела")
                .tag("mode", "true".equals(event.details().get("manual")) ? "manual" : "auto")
                .register(registry)
                .record(Duration.between(appeal.getCreatedAt(), Instant.now())));
    }

    /** Ответ по существу: итог и уложились ли в срок по закону. */
    private void resolved(AppealDomainEvent event) {
        appeals.findById(event.appealId()).ifPresent(appeal -> Counter.builder("appeals.resolved")
                .description("Дан ответ по существу")
                .tag("outcome", String.valueOf(event.details().get("outcome")))
                .tag("in_time", inTime(appeal))
                .register(registry)
                .increment());
    }

    private String inTime(Appeal appeal) {
        if (appeal.getLegalDeadline() == null || appeal.getResolvedAt() == null) {
            return "unknown";
        }
        return Boolean.toString(!LocalDate.ofInstant(appeal.getResolvedAt(), zone).isAfter(appeal.getLegalDeadline()));
    }

    private static double secondsSince(Instant moment) {
        return moment == null ? 0 : Duration.between(moment, Instant.now()).toSeconds();
    }

    private static int stateCode(CircuitBreaker.State state) {
        return switch (state) {
            case OPEN, FORCED_OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 0;
        };
    }
}
