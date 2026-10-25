package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.ClassifiedResponse;
import ru.mtuci.appeals.api.ApiModels.ClassifyRequest;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.domain.AppealStatus;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Ручная классификация (решение 31): тип обращения появился в справочнике раньше правила
 * маршрутизации. Обращение не теряется в очереди «мёртвых» писем: отдел выбирает руководитель.
 */
class ManualClassificationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void appealWithoutRuleWaitsForSupervisor() {
        UUID appeal = createAppeal(agentQuestion()).id();

        AppealDetailResponse waiting = awaitClassificationRequired(appeal);
        assertThat(waiting.department()).isNull();
        // срок по закону идёт и без отдела
        assertThat(waiting.legalDeadline()).isNotNull();
        assertThat(waiting.history()).extracting(HistoryResponse::action).contains("CLASSIFICATION_REQUIRED");

        // руководитель любого отдела видит его в своей очереди, специалист не видит
        assertThat(as(SUPPORT_SUPERVISOR).getForObject("/api/appeals", AppealSummaryResponse[].class))
                .extracting(AppealSummaryResponse::id).contains(appeal);
        assertThat(as(SUPPORT_SPECIALIST).getForEntity("/api/appeals/{id}", String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void supervisorSendsAppealToDepartment() {
        UUID appeal = createAppeal(agentQuestion()).id();
        awaitClassificationRequired(appeal);

        // специалист выбирать отдел не может
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/classify",
                new ClassifyRequest("POLICY"), String.class, appeal).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ClassifiedResponse result = as(CLAIMS_SUPERVISOR).postForObject("/api/appeals/{id}/classify",
                new ClassifyRequest("POLICY"), ClassifiedResponse.class, appeal);
        assertThat(result.departmentCode()).isEqualTo("POLICY");

        AppealDetailResponse routed = getAppeal(appeal);
        assertThat(routed.status()).isEqualTo(AppealStatus.ROUTED);
        assertThat(routed.department().code()).isEqualTo("POLICY");
        assertThat(routed.deadlineAt()).isNotNull();
        assertThat(routed.history()).extracting(HistoryResponse::action).contains("CLASSIFIED");

        // второй раз направить нельзя, а руководитель убытков обращение больше не видит
        assertThat(as(CLAIMS_SUPERVISOR).postForEntity("/api/appeals/{id}/classify",
                new ClassifyRequest("CLAIMS"), String.class, appeal).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private AppealDetailResponse awaitClassificationRequired(UUID appeal) {
        return await().atMost(Duration.ofSeconds(15))
                .until(() -> getAppeal(appeal), item -> item.status() == AppealStatus.NEEDS_CLASSIFICATION);
    }

    private CreateAppealRequest agentQuestion() {
        prepareSchema();
        return new CreateAppealRequest(CASCO_CONTRACT, null, null, "PARTNER", "AGENT", Map.of(),
                "Вопрос о страховом агенте", "Агент оформил полис, но не выдал квитанцию об оплате.");
    }

    /** Как будто администратор завёл новую категорию и тип, а правило маршрутизации: ещё нет. */
    private void prepareSchema() {
        jdbc.sql("insert into appeal_category (code, label, sort_order) values ('PARTNER', 'Работа агентов', 90) "
                + "on conflict do nothing").update();
        jdbc.sql("insert into appeal_type (category, code, label, sort_order) values ('PARTNER', 'AGENT', "
                + "'Вопрос о страховом агенте', 1) on conflict do nothing").update();
        jdbc.sql("insert into appeal_type_product (category, type_code, product_code) values ('PARTNER', 'AGENT', 'CASCO') "
                + "on conflict do nothing").update();
    }
}
