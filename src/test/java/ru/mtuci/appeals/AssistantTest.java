package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.mtuci.appeals.api.ApiModels.AnalyticsResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AssistantAdviceResponse;
import ru.mtuci.appeals.api.ApiModels.AssistantFeedbackRequest;
import ru.mtuci.appeals.api.ApiModels.AssistantSettingsRequest;
import ru.mtuci.appeals.api.ApiModels.AssistantSettingsResponse;
import ru.mtuci.appeals.api.ApiModels.AssistantUsage;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.DraftResponse;
import ru.mtuci.appeals.api.ApiModels.IntegrationStatusResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.api.ApiModels.SignalBadge;
import ru.mtuci.appeals.api.ApiModels.SignalResponse;
import ru.mtuci.appeals.api.ApiModels.SimilarAppealResponse;
import ru.mtuci.appeals.TestIdentityProvider.TestUser;
import ru.mtuci.appeals.assistant.LocalModelEngine;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealStatus;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Помощник сотрудника (решение 35): сигналы в тексте клиента, подсказка «похоже на претензию»,
 * похожие решённые обращения, черновик по шаблону, учёт принятых подсказок и локальная модель
 * с переходом на правила, когда она недоступна.
 */
class AssistantTest extends IntegrationTest {

    @Autowired
    private LocalModelEngine model;

    @AfterEach
    void backToRules() {
        TestLocalModel.available(true);
        model.circuitBreaker().reset();
        as(ADMIN).put("/api/admin/assistant", new AssistantSettingsRequest("RULES", "Тест завершён"));
    }

    @Test
    void signalsAreFoundInTextAndMessagesAndStayInternal() {
        UUID id = routed(withText(loginProblem(),
                "Искренне надеюсь на помощь. Если не почините вход, напишу жалобу в Банк России."));

        AppealSummaryResponse queued = queueItem(SUPPORT_SPECIALIST, id);
        // «искренне» — не иск: признаки ищутся по словам, а не по буквам
        assertThat(queued.signals()).extracting(SignalBadge::code).containsExactly("REGULATOR");
        assertThat(queued.signals().get(0).risk()).isTrue();

        assertThat(as(ANNA).postForEntity("/api/appeals/{id}/messages",
                messageForm("Требую вернуть 1 500 руб. за подписку, которой не пользуюсь"), String.class, id)
                .getStatusCode().is2xxSuccessful()).isTrue();
        AssistantAdviceResponse advice = advice(SUPPORT_SPECIALIST, id);
        assertThat(advice.signals()).extracting(SignalResponse::code).containsExactly("REGULATOR", "PAYMENT_DEMAND");
        assertThat(advice.signals().get(1).excerpt()).contains("1 500 руб");
        assertThat(advice.signals().get(0).hint()).isNotBlank();

        // клиенту — ни сигналов, ни подсказок: это внутренняя оценка его обращения
        assertThat(queueItem(ANNA, id).signals()).isEmpty();
        assertThat(as(ANNA).getForEntity("/api/appeals/{id}/assistant", String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // и сотруднику чужого отдела тоже
        assertThat(as(CLAIMS_SPECIALIST).getForEntity("/api/appeals/{id}/assistant", String.class, id).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    void pretensionHintShowsLegalDeadlineAndDecisionIsCounted() {
        AnalyticsResponse before = analytics();
        UUID id = routed(withText(cascoClaim(),
                "Не согласен с размером выплаты по прошлому случаю: требую доплатить 40 000 ₽."));

        AssistantAdviceResponse advice = advice(CLAIMS_SPECIALIST, id);
        assertThat(advice.engine()).isEqualTo("RULES");
        assertThat(advice.typeHint()).isNotNull();
        assertThat(advice.typeHint().subcategory()).isEqualTo("PRETENSION");
        assertThat(advice.typeHint().reasons()).isNotEmpty();
        assertThat(advice.typeHint().legalDeadline()).isNotNull();
        assertThat(advice.typeHint().legalBasis()).contains("123-ФЗ");

        // решение по подсказке, которой не показывали, не принимается
        assertThat(feedback(CLAIMS_SPECIALIST, id, "DRAFT", "ACCEPTED").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(feedback(CLAIMS_SPECIALIST, id, "TYPE_HINT", "ACCEPTED").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(advice(CLAIMS_SPECIALIST, id).decisions()).containsEntry("TYPE_HINT", "ACCEPTED");

        AnalyticsResponse after = analytics();
        assertThat(usage(after, "TYPE_HINT", "RULES").shown() - usage(before, "TYPE_HINT", "RULES").shown()).isEqualTo(1);
        assertThat(usage(after, "TYPE_HINT", "RULES").accepted().count()
                - usage(before, "TYPE_HINT", "RULES").accepted().count()).isEqualTo(1);
    }

    @Test
    void similarCasesHideOtherClientsAndDraftFollowsTemplate() {
        UUID solved = routed(loginProblem());
        startWork(SUPPORT_SPECIALIST, solved);
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/resolve", new ResolveAppealRequest(
                AppealOutcome.EXPLAINED, "Анна, код приходил с задержкой из-за сбоя оператора, отправку SMS исправили.",
                null, null), String.class, solved).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID fresh = routed(loginProblem());
        AssistantAdviceResponse advice = advice(SUPPORT_SPECIALIST, fresh);
        assertThat(advice.similar()).isNotEmpty();
        SimilarAppealResponse first = advice.similar().get(0);
        assertThat(first.outcome()).isEqualTo(AppealOutcome.EXPLAINED);
        assertThat(first.answer()).startsWith("[клиент], код приходил с задержкой").doesNotContain("Анна");

        DraftResponse draft = draft(SUPPORT_SPECIALIST, fresh, "EXPLAINED", null);
        assertThat(draft.templateTitle()).isEqualTo("Техническая проблема");
        assertThat(draft.source()).isEqualTo("RULES");
        assertThat(draft.text()).contains("Анна Смирнова", getAppeal(fresh).publicNumber()).doesNotContain("{номер}");

        // итоговый тип из формы ответа выбирает шаблон
        DraftResponse pretension = draft(SUPPORT_SPECIALIST, fresh, "REJECTED", "COMPLAINT/PRETENSION");
        assertThat(pretension.templateTitle()).isEqualTo("Претензия: отказ");
        assertThat(pretension.text()).contains("финансовому уполномоченному");
        assertThat(as(SUPPORT_SPECIALIST).getForEntity("/api/appeals/{id}/assistant/draft?outcome=REJECTED&type=NO/SUCH",
                String.class, fresh).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void localModelAnswersAndRulesTakeOverWhenItIsDown() {
        assertThat(as(SUPPORT_SUPERVISOR).exchange("/api/admin/assistant", HttpMethod.PUT,
                new HttpEntity<>(new AssistantSettingsRequest("LOCAL_MODEL", "Пилот")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        AssistantSettingsResponse settings = as(ADMIN).exchange("/api/admin/assistant", HttpMethod.PUT,
                new HttpEntity<>(new AssistantSettingsRequest("LOCAL_MODEL", "Пилот локальной модели в отделе убытков")),
                AssistantSettingsResponse.class).getBody();
        assertThat(settings.engine()).isEqualTo("LOCAL_MODEL");
        assertThat(as(ADMIN).getForObject("/api/integrations", IntegrationStatusResponse[].class))
                .extracting(IntegrationStatusResponse::code).contains("ASSISTANT");

        UUID id = routed(withText(cascoClaim(), "Выплату занизили, требую доплатить разницу."));
        AssistantAdviceResponse advice = advice(CLAIMS_SPECIALIST, id);
        assertThat(advice.engine()).isEqualTo("LOCAL_MODEL");
        assertThat(advice.notice()).isNull();
        assertThat(advice.typeHint().source()).isEqualTo("LOCAL_MODEL");
        assertThat(advice.typeHint().reasons()).containsExactly("требую доплатить");
        DraftResponse draft = draft(CLAIMS_SPECIALIST, id, "SATISFIED", null);
        assertThat(draft.source()).isEqualTo("LOCAL_MODEL");
        assertThat(draft.text()).startsWith(TestLocalModel.DRAFT_PREFIX);

        // модель упала — подсказки дают правила, сотрудник видит почему
        TestLocalModel.available(false);
        AssistantAdviceResponse degraded = advice(CLAIMS_SPECIALIST, id);
        assertThat(degraded.notice()).contains("правила");
        assertThat(degraded.typeHint().source()).isEqualTo("RULES");
        DraftResponse fallback = draft(CLAIMS_SPECIALIST, id, "SATISFIED", null);
        assertThat(fallback.source()).isEqualTo("RULES");
        assertThat(fallback.text()).contains("Событие признано страховым случаем");
    }

    // ---------- помощники теста ----------

    private UUID routed(CreateAppealRequest request) {
        UUID id = createAppeal(request).id();
        awaitRouted(id);
        return id;
    }

    private static CreateAppealRequest withText(CreateAppealRequest request, String description) {
        return new CreateAppealRequest(request.contractId(), request.productCode(), request.policyNumber(),
                request.category(), request.subcategory(), request.details(), request.subject(), description);
    }

    private static HttpEntity<MultiValueMap<String, Object>> messageForm(String body) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("body", body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }

    private AppealSummaryResponse queueItem(TestUser user, UUID id) {
        return Arrays.stream(as(user).getForObject("/api/appeals", AppealSummaryResponse[].class))
                .filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private AssistantAdviceResponse advice(TestUser user, UUID id) {
        ResponseEntity<AssistantAdviceResponse> response = as(user).getForEntity("/api/appeals/{id}/assistant",
                AssistantAdviceResponse.class, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private DraftResponse draft(TestUser user, UUID id, String outcome, String type) {
        String uri = "/api/appeals/{id}/assistant/draft?outcome={outcome}" + (type == null ? "" : "&type={type}");
        ResponseEntity<DraftResponse> response = type == null
                ? as(user).getForEntity(uri, DraftResponse.class, id, outcome)
                : as(user).getForEntity(uri, DraftResponse.class, id, outcome, type);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> feedback(TestUser user, UUID id, String kind, String decision) {
        return as(user).postForEntity("/api/appeals/{id}/assistant/feedback",
                new AssistantFeedbackRequest(kind, decision), String.class, id);
    }

    private void startWork(TestUser user, UUID id) {
        assertThat(as(user).postForEntity("/api/appeals/{id}/status", new ChangeStatusRequest(AppealStatus.IN_PROGRESS),
                String.class, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AnalyticsResponse analytics() {
        return as(ADMIN).getForObject("/api/admin/analytics?days=30", AnalyticsResponse.class);
    }

    private static AssistantUsage usage(AnalyticsResponse analytics, String kind, String source) {
        return analytics.assistant().stream()
                .filter(item -> item.kind().equals(kind) && item.source().equals(source)).findFirst()
                .orElse(new AssistantUsage(kind, source, 0, ru.mtuci.appeals.api.ApiModels.Rate.of(0, 0), 0));
    }
}
