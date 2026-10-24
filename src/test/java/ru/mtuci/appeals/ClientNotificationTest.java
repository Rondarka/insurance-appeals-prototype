package ru.mtuci.appeals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ExtendDeadlineRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.messaging.AppealEvent;
import ru.mtuci.appeals.service.ClientNotificationService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Уведомления клиенту (решение 37): подсистема решает, когда сообщить клиенту, и передаёт
 * запрос сервису коммуникаций событием appeal.notification.*. Запросы видны в журнале
 * событий: туда приходит всё, что опубликовано в обменник.
 */
class ClientNotificationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClientNotificationService notifications;

    @Test
    void clientIsToldAboutRegistrationMessagesExtensionAndAnswer() {
        UUID id = createAppeal(loginProblem()).id();
        awaitRouted(id);
        JsonNode registered = awaitRequest(id, "appeal_registered");
        assertThat(registered.path("counterpartyId").asText()).isEqualTo(ANNA_COUNTERPARTY.toString());
        assertThat(registered.path("appealNumber").asText()).isEqualTo(getAppeal(id).publicNumber());
        assertThat(registered.path("legalDeadline").asText()).isEqualTo(getAppeal(id).legalDeadline().toString());
        // ни имени, ни контактов, ни текста: контакты сервис коммуникаций берёт из CDI
        assertThat(registered.has("customerName") || registered.has("email")).isFalse();

        status(id, AppealStatus.IN_PROGRESS);
        as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/messages",
                multipart("Уточните, пожалуйста, модель телефона"), String.class, id);
        awaitRequest(id, "new_message");

        as(SUPPORT_SUPERVISOR).postForObject("/api/appeals/{id}/extend-deadline",
                new ExtendDeadlineRequest(5, "Нужна выгрузка журнала входов от оператора SMS"), AppealDetailResponse.class, id);
        assertThat(awaitRequest(id, "deadline_extended").path("legalDeadline").asText())
                .isEqualTo(getAppeal(id).legalDeadline().toString());

        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/resolve", new ResolveAppealRequest(
                AppealOutcome.EXPLAINED, "Код приходил с задержкой из-за сбоя оператора, отправку SMS исправили.", null, null),
                String.class, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(awaitRequest(id, "answer_ready").path("outcome").asText()).isEqualTo("EXPLAINED");

        // ответ по существу даёт одно уведомление, а не «новое сообщение» и «ответ готов»
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() -> templates(id).size() == 4);
        assertThat(templates(id)).containsExactlyInAnyOrder(
                "APPEAL_REGISTERED", "NEW_MESSAGE", "DEADLINE_EXTENDED", "ANSWER_READY");
        assertThat(getAppeal(id).history()).extracting(HistoryResponse::action).contains("NOTIFICATION_REQUESTED");
    }

    @Test
    void repeatedEventDoesNotNotifyTwice() {
        UUID id = createAppeal(loginProblem()).id();
        awaitRequest(id, "appeal_registered");
        AppealEvent repeated = new AppealEvent(UUID.fromString(jdbc.sql("""
                        select event_id from event_audit where appeal_id = :id and event_type = 'APPEAL_CREATED'""")
                .param("id", id).query(String.class).single()),
                "APPEAL_CREATED", id, "appeal.created.technical.login", Map.of(), Instant.now());

        notifications.handle(repeated);

        assertThat(templates(id)).containsExactly("APPEAL_REGISTERED");
    }

    private JsonNode awaitRequest(UUID appealId, String template) {
        String payload = await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.sql("""
                                select payload from event_audit
                                where appeal_id = :id and routing_key = :key
                                order by received_at desc limit 1""")
                        .param("id", appealId).param("key", "appeal.notification." + template)
                        .query(String.class).optional().orElse(null),
                found -> found != null);
        try {
            return objectMapper.readTree(payload).path("details");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> templates(UUID appealId) {
        return jdbc.sql("select template from notification_request where appeal_id = :id")
                .param("id", appealId).query(String.class).list();
    }

    private void status(UUID id, AppealStatus status) {
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/status", new ChangeStatusRequest(status),
                String.class, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static HttpEntity<MultiValueMap<String, Object>> multipart(String body) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("body", body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }
}
