package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.NewNormVersionRequest;
import ru.mtuci.appeals.api.ApiModels.NormVersionResponse;
import ru.mtuci.appeals.api.ApiModels.RoutingRuleResponse;
import ru.mtuci.appeals.api.ApiModels.RoutingSettingsResponse;
import ru.mtuci.appeals.api.ApiModels.SaveRoutingRuleRequest;
import ru.mtuci.appeals.api.ApiModels.SettingsAuditResponse;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.DeadlineCounting;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настройки администратора (решение 34): правила маршрутизации и нормативы меняются без
 * программиста, но срок отдела не может быть длиннее срока по закону, и каждое изменение
 * попадает в журнал. Меняется правило отдельного типа — после теста оно удаляется.
 */
class SettingsTest extends IntegrationTest {

    private static final String UPLOAD = "DOCUMENT_UPLOAD";
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void restore() {
        jdbc.sql("delete from routing_rule where category = 'TECHNICAL' and subcategory = :type").param("type", UPLOAD).update();
        jdbc.sql("delete from deadline_norm where code = 'GENERAL' and valid_from > current_date").update();
        jdbc.sql("update deadline_norm set valid_to = null where code = 'GENERAL' and valid_from <= current_date").update();
    }

    @Test
    void rulesShowLimitsSetByLaw() {
        RoutingSettingsResponse settings = as(ADMIN).getForObject("/api/admin/routing-rules", RoutingSettingsResponse.class);

        // общий срок ответа — 15 раб. дн., претензия — тоже 15; страховые случаи — 20 дней; минус 2 дня запаса
        assertThat(rule(settings, "TECHNICAL", "GENERAL").maxSlaHours()).isEqualTo(13 * 24);
        assertThat(rule(settings, "COMPLAINT", "GENERAL").maxSlaHours()).isEqualTo(13 * 24);
        assertThat(rule(settings, "CLAIM", "GENERAL").maxSlaHours()).isEqualTo(18 * 24);
        assertThat(rule(settings, "TECHNICAL", "GENERAL").legalLimit()).contains("Закон РФ № 4015-1");
    }

    @Test
    void adminChangesRuleWithinLawAndChangeIsAudited() {
        put(new SaveRoutingRuleRequest("TECHNICAL", UPLOAD, "SUPPORT", AppealPriority.HIGH, 24, "Файлы — частая причина жалоб"),
                RoutingSettingsResponse.class);
        put(new SaveRoutingRuleRequest("TECHNICAL", UPLOAD, "SUPPORT", AppealPriority.HIGH, 36, "Уточнили норматив отдела"),
                RoutingSettingsResponse.class);

        SettingsAuditResponse last = audit()[0];
        assertThat(last.entityKey()).isEqualTo("TECHNICAL/" + UPLOAD);
        assertThat(last.action()).isEqualTo("UPDATED");
        assertThat(last.before()).contains("\"slaHours\": 24");
        assertThat(last.after()).contains("\"slaHours\": 36");
        assertThat(last.reason()).isEqualTo("Уточнили норматив отдела");

        // новое обращение этого типа маршрутизируется по новому правилу
        AppealDetailResponse appeal = awaitRouted(createAppeal(uploadProblem()).id());
        assertThat(appeal.priority()).isEqualTo(AppealPriority.HIGH);
        assertThat(Duration.between(routedAt(appeal.id()), appeal.deadlineAt()).toHours()).isEqualTo(36);
    }

    @Test
    void departmentDeadlineLongerThanLawIsRejected() {
        ResponseEntity<ErrorResponse> response = put(new SaveRoutingRuleRequest("TECHNICAL", "GENERAL", "SUPPORT",
                AppealPriority.NORMAL, 400, "Отдел не справляется"), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("не больше 312 ч").contains("15 раб. дн.");
        assertThat(rule(as(ADMIN).getForObject("/api/admin/routing-rules", RoutingSettingsResponse.class),
                "TECHNICAL", "GENERAL").slaHours()).isEqualTo(12);
    }

    @Test
    void ruleSavedBeforeLawCannotOutliveLegalDeadline() {
        // правило, заведённое до проверки, — срок отдела всё равно не позже срока по закону
        long departmentId = jdbc.sql("select id from department where code = 'SUPPORT'").query(Long.class).single();
        jdbc.sql("insert into routing_rule (category, subcategory, department_id, priority, sla_hours) "
                        + "values ('TECHNICAL', :type, :department, 'NORMAL', 2000)")
                .param("type", UPLOAD).param("department", departmentId).update();

        AppealDetailResponse appeal = awaitRouted(createAppeal(uploadProblem()).id());

        assertThat(appeal.deadlineAt()).isBeforeOrEqualTo(appeal.legalDeadline().plusDays(1).atStartOfDay(MOSCOW).toInstant());
    }

    @Test
    void onlyAdministratorChangesSettings() {
        assertThat(as(SUPPORT_SUPERVISOR).getForEntity("/api/admin/routing-rules", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as(ANNA).getForEntity("/api/admin/norms", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void newNormVersionOnlyFromFutureDate() {
        LocalDate today = LocalDate.now(MOSCOW);
        ResponseEntity<ErrorResponse> retroactive = as(ADMIN).postForEntity("/api/admin/norms",
                norm(today), ErrorResponse.class);
        assertThat(retroactive.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        LocalDate from = today.plusDays(30);
        NormVersionResponse[] norms = as(ADMIN).postForObject("/api/admin/norms", norm(from), NormVersionResponse[].class);

        // прежняя версия действует до дня перед новой, новая — со ссылкой на акт
        NormVersionResponse previous = Arrays.stream(norms)
                .filter(item -> item.code().equals("GENERAL") && item.validTo() != null).findFirst().orElseThrow();
        NormVersionResponse next = Arrays.stream(norms)
                .filter(item -> item.code().equals("GENERAL") && from.equals(item.validFrom())).findFirst().orElseThrow();
        assertThat(previous.validTo()).isEqualTo(from.minusDays(1));
        assertThat(next.termDays()).isEqualTo(10);
        assertThat(next.legalBasis()).isEqualTo("Федеральный закон (пример для проверки)");
        assertThat(audit()[0].entity()).isEqualTo("DEADLINE_NORM");
    }

    private static NewNormVersionRequest norm(LocalDate from) {
        return new NewNormVersionRequest("GENERAL", 10, DeadlineCounting.WORKING_DAYS, 10,
                "Федеральный закон (пример для проверки)", from, "Изменение закона");
    }

    private <T> ResponseEntity<T> put(SaveRoutingRuleRequest request, Class<T> type) {
        return as(ADMIN).exchange("/api/admin/routing-rules", HttpMethod.PUT, new HttpEntity<>(request), type);
    }

    private SettingsAuditResponse[] audit() {
        return as(ADMIN).getForObject("/api/admin/settings-audit", SettingsAuditResponse[].class);
    }

    private static RoutingRuleResponse rule(RoutingSettingsResponse settings, String category, String subcategory) {
        return settings.rules().stream()
                .filter(item -> item.category().equals(category) && item.subcategory().equals(subcategory))
                .findFirst().orElseThrow();
    }

    private java.time.Instant routedAt(UUID appeal) {
        return jdbc.sql("select max(created_at) from appeal_history where appeal_id = :id and action = 'ROUTED'")
                .param("id", appeal).query(java.sql.Timestamp.class).single().toInstant();
    }

    private static CreateAppealRequest uploadProblem() {
        return new CreateAppealRequest(CASCO_CONTRACT, null, null, "TECHNICAL", UPLOAD,
                Map.of("systemSection", "Загрузка документов", "device", "Android, Chrome", "errorText", "Файл не загружается"),
                "Не загружается справка", "Справка в PDF не прикрепляется к обращению.");
    }
}
