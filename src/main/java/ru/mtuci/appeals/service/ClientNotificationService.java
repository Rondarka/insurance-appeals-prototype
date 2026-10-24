package ru.mtuci.appeals.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.messaging.AppealEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Уведомления клиенту (решение 37). Подсистема решает, когда клиенту нужно сообщить о его
 * обращении: о регистрации и сроке ответа, о новом сообщении сотрудника, о продлении срока,
 * о готовом ответе по существу. Как доставить (SMS, почта, пуш), каким текстом и по каким
 * контактам, решает сервис коммуникаций страховой: он получает событие
 * CLIENT_NOTIFICATION_REQUESTED с кодом шаблона и параметрами.
 *
 * В событии нет ни имени, ни контактов клиента, ни текста ответа: только идентификатор
 * клиента, номер обращения и даты. Контакты сервис коммуникаций берёт из CDI, текст ответа
 * клиент читает в личном кабинете.
 *
 * Уведомления строятся по событиям самой подсистемы: издатель событий для этого не менялся.
 */
@Service
public class ClientNotificationService {

    public static final String EVENT_TYPE = "CLIENT_NOTIFICATION_REQUESTED";
    private static final Map<String, String> LABELS = Map.of(
            "APPEAL_REGISTERED", "обращение зарегистрировано, срок ответа",
            "NEW_MESSAGE", "новое сообщение от страховой",
            "DEADLINE_EXTENDED", "срок рассмотрения продлён",
            "ANSWER_READY", "готов ответ по существу");

    private final AppealRepository appeals;
    private final AppealHistoryRepository history;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;

    public ClientNotificationService(AppealRepository appeals, AppealHistoryRepository history,
                                     ApplicationEventPublisher events, JdbcClient jdbc) {
        this.appeals = appeals;
        this.history = history;
        this.events = events;
        this.jdbc = jdbc;
    }

    /** Событие подсистемы → запрос уведомления; повтор события запроса не повторяет. */
    @Transactional
    public void handle(AppealEvent event) {
        String template = template(event);
        if (template == null) {
            return;
        }
        Appeal appeal = appeals.findById(event.appealId()).orElse(null);
        if (appeal == null) {
            return;
        }
        // ответ по существу приходит и сообщением, и сменой статуса; клиенту нужно одно уведомление
        if ("NEW_MESSAGE".equals(template) && isAnswerOnMerits(appeal)) {
            return;
        }
        Instant now = Instant.now();
        int recorded = jdbc.sql("""
                        insert into notification_request (source_event_id, appeal_id, template, requested_at)
                        values (:event, :appeal, :template, :at)
                        on conflict (source_event_id) do nothing""")
                .param("event", event.eventId()).param("appeal", appeal.getId())
                .param("template", template).param("at", Timestamp.from(now))
                .update();
        if (recorded == 0) {
            return;
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("template", template);
        details.put("counterpartyId", appeal.getCounterpartyId().toString());
        details.put("appealNumber", appeal.getPublicNumber());
        details.put("registeredAt", appeal.getCreatedAt().toString());
        if (appeal.getLegalDeadline() != null) {
            details.put("legalDeadline", appeal.getLegalDeadline().toString());
        }
        if ("ANSWER_READY".equals(template) && appeal.getOutcome() != null) {
            details.put("outcome", appeal.getOutcome().name());
        }
        events.publishEvent(new AppealDomainEvent(EVENT_TYPE, appeal.getId(),
                "appeal.notification." + template.toLowerCase(Locale.ROOT), details));
        history.save(new AppealHistory(appeal, "NOTIFICATION_REQUESTED",
                "Сервису коммуникаций передано уведомление клиенту: " + LABELS.get(template), "Подсистема", now));
    }

    /** О чём сообщить клиенту по этому событию; null, если клиенту сообщать нечего. */
    private static String template(AppealEvent event) {
        return switch (event.eventType()) {
            case "APPEAL_CREATED" -> "APPEAL_REGISTERED";
            case "APPEAL_MESSAGE_ADDED" -> "appeal.message.employee".equals(event.routingKey()) ? "NEW_MESSAGE" : null;
            case "APPEAL_DEADLINE_EXTENDED" -> "DEADLINE_EXTENDED";
            case "APPEAL_STATUS_CHANGED" -> "appeal.status.resolved".equals(event.routingKey()) ? "ANSWER_READY" : null;
            default -> null;
        };
    }

    /** Последнее сообщение сотрудника отправлено в момент решения, значит, это ответ по существу. */
    private boolean isAnswerOnMerits(Appeal appeal) {
        return appeal.getResolvedAt() != null && jdbc.sql("""
                        select exists(select 1 from appeal_message
                                      where appeal_id = :appeal and author_type = 'EMPLOYEE' and created_at = :resolved)
                           and not exists(select 1 from appeal_message
                                          where appeal_id = :appeal and author_type = 'EMPLOYEE' and created_at > :resolved)""")
                .param("appeal", appeal.getId()).param("resolved", Timestamp.from(appeal.getResolvedAt()))
                .query(Boolean.class).single();
    }
}
