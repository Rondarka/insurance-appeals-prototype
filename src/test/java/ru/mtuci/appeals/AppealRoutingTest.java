package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.messaging.AppealEvent;
import ru.mtuci.appeals.messaging.RabbitTopology;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/** Главный сценарий: обращение создано → маршрутизировано по правилу → события в журнале аудита. */
class AppealRoutingTest extends IntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void createdAppealIsRoutedByRuleAndAudited() {
        AppealDetailResponse created = createAppeal(cascoClaim());

        // до маршрутизации отдела нет: это окно согласованности событийной архитектуры
        assertThat(created.status()).isEqualTo(AppealStatus.PENDING_ROUTING);
        assertThat(created.department()).isNull();
        assertThat(created.contract().policyNumber()).isEqualTo("КАСКО-2026-004821");

        AppealDetailResponse routed = awaitRouted(created.id());

        // правило CLAIM / GENERAL: урегулирование убытков, высокий приоритет, срок 8 часов
        assertThat(routed.status()).isEqualTo(AppealStatus.ROUTED);
        assertThat(routed.department().code()).isEqualTo("CLAIMS");
        assertThat(routed.priority()).isEqualTo(AppealPriority.HIGH);
        assertThat(Duration.between(routed.createdAt(), routed.deadlineAt()))
                .isCloseTo(Duration.ofHours(8), Duration.ofMinutes(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(auditedEvents(created.id()))
                        .extracting(EventAuditResponse::eventType, EventAuditResponse::routingKey)
                        .contains(
                                tuple("APPEAL_CREATED", "appeal.created.claim.auto"),
                                tuple("APPEAL_ROUTED", "appeal.routed.claims")));
    }

    @Test
    void repeatedCreatedEventDoesNotRouteTwice() {
        AppealDetailResponse created = createAppeal(cascoClaim());
        awaitRouted(created.id());

        // Повторная доставка факта создания. Идентификатор события новый намеренно:
        // маршрутизация отсекает дубли по состоянию обращения, а не по ключу сообщения.
        String routingKey = "appeal.created.claim.auto";
        rabbitTemplate.convertAndSend(RabbitTopology.APPEAL_EXCHANGE, routingKey, new AppealEvent(
                UUID.randomUUID(), "APPEAL_CREATED", created.id(), routingKey, Map.of(), Instant.now()));

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() ->
                assertThat(historyActions(created.id())).containsOnlyOnce("ROUTED"));
    }

    private List<EventAuditResponse> auditedEvents(UUID appealId) {
        EventAuditResponse[] events = rest.getForObject("/api/events", EventAuditResponse[].class);
        return Arrays.stream(events).filter(event -> appealId.equals(event.appealId())).toList();
    }

    private List<String> historyActions(UUID appealId) {
        return getAppeal(appealId).history().stream().map(HistoryResponse::action).toList();
    }
}
