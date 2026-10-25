package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.mtuci.appeals.api.ApiModels.AnalyticsResponse;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.DepartmentOnTime;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ShareItem;
import ru.mtuci.appeals.api.ApiModels.TypeCorrection;
import ru.mtuci.appeals.api.ApiModels.WeekSeries;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Аналитика обращений (решение 23). Обращения от других тестов остаются в базе, поэтому
 * проверяется прирост показателей: снимок до, действия, снимок после.
 */
class AnalyticsTest extends IntegrationTest {

    private static final String ANSWER = "Вход в кабинет восстановлен, сбой был на стороне оператора SMS.";

    @Test
    void indicatorsFollowAppealsAndFinalType() {
        AnalyticsResponse before = analytics("CASCO");

        UUID corrected = routedLoginProblem();
        startWork(corrected);
        assertThat(resolve(corrected, "TECHNICAL", "PERSONAL_ACCOUNT").getStatusCode()).isEqualTo(HttpStatus.OK);
        AppealDetailResponse resolved = getAppeal(corrected);
        assertThat(resolved.finalCategory()).isEqualTo("TECHNICAL");
        assertThat(resolved.finalSubcategory()).isEqualTo("PERSONAL_ACCOUNT");
        routedLoginProblem();

        AnalyticsResponse after = analytics("CASCO");
        assertThat(after.product()).isEqualTo("CASCO");
        assertThat(after.summary().received() - before.summary().received()).isEqualTo(2);
        assertThat(after.summary().resolved() - before.summary().resolved()).isEqualTo(1);
        assertThat(after.summary().resolvedOnTime().count() - before.summary().resolvedOnTime().count()).isEqualTo(1);
        assertThat(after.summary().typeCorrected().count() - before.summary().typeCorrected().count()).isEqualTo(1);

        // показатели: по итоговому типу, а расхождение видно отдельной строкой
        assertThat(count(after.types(), "TECHNICAL/PERSONAL_ACCOUNT") - count(before.types(), "TECHNICAL/PERSONAL_ACCOUNT"))
                .isEqualTo(1);
        assertThat(count(after.types(), "TECHNICAL/LOGIN") - count(before.types(), "TECHNICAL/LOGIN")).isEqualTo(1);
        assertThat(corrections(after) - corrections(before)).isEqualTo(1);

        DepartmentOnTime support = department(after, "SUPPORT");
        assertThat(support.onTime().count() - department(before, "SUPPORT").onTime().count()).isEqualTo(1);
        assertThat(support.open() - department(before, "SUPPORT").open()).isEqualTo(1);

        // обращения сегодняшние: прирост в последней неделе линии КАСКО
        WeekSeries casco = after.weekly().get(0);
        assertThat(casco.code()).isEqualTo("CASCO");
        assertThat(after.weekly()).hasSize(1);
        assertThat(lastWeek(after) - lastWeek(before)).isEqualTo(2);
        assertThat(after.weeks().get(0).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);

        // доли продуктов: от всех обращений, фильтр продукта их не сужает
        assertThat(after.products()).extracting(ShareItem::code).contains("CASCO");
        assertThat(after.products().stream().mapToLong(item -> item.rate().count()).sum())
                .isGreaterThanOrEqualTo(after.summary().received());
    }

    @Test
    void unknownFinalTypeIsRejectedAndReopeningDropsIt() {
        UUID appeal = routedLoginProblem();
        startWork(appeal);
        assertThat(resolve(appeal, "TECHNICAL", "NO_SUCH_TYPE").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // без итогового типа: исходный подтверждён
        assertThat(resolve(appeal, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getAppeal(appeal).finalSubcategory()).isEqualTo("LOGIN");

        startWork(appeal);
        AppealDetailResponse reopened = getAppeal(appeal);
        assertThat(reopened.outcome()).isNull();
        assertThat(reopened.finalCategory()).isNull();
    }

    @Test
    void analyticsIsForAdministratorWithinSanePeriod() {
        assertThat(as(SUPPORT_SUPERVISOR).getForEntity("/api/admin/analytics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as(ADMIN).getForEntity("/api/admin/analytics?days=3", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(as(ADMIN).getForEntity("/api/admin/analytics?product=YACHT", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(as(ADMIN).getForObject("/api/admin/analytics", AnalyticsResponse.class).outcomes())
                .extracting(ShareItem::code)
                .containsExactly("SATISFIED", "PARTIALLY_SATISFIED", "REJECTED", "EXPLAINED");
    }

    private AnalyticsResponse analytics(String product) {
        return as(ADMIN).getForObject("/api/admin/analytics?days=30&product={product}", AnalyticsResponse.class, product);
    }

    private UUID routedLoginProblem() {
        UUID id = createAppeal(loginProblem()).id();
        awaitRouted(id);
        return id;
    }

    private void startWork(UUID appeal) {
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private org.springframework.http.ResponseEntity<String> resolve(UUID appeal, String category, String type) {
        return as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/resolve",
                new ResolveAppealRequest(AppealOutcome.SATISFIED, ANSWER, category, type), String.class, appeal);
    }

    private static long count(List<ShareItem> items, String code) {
        return items.stream().filter(item -> item.code().equals(code)).mapToLong(item -> item.rate().count()).sum();
    }

    private static long corrections(AnalyticsResponse analytics) {
        return analytics.corrections().stream()
                .filter(item -> item.initial().startsWith("Технич") && item.finalType().startsWith("Технич"))
                .mapToLong(TypeCorrection::count).sum();
    }

    private static long lastWeek(AnalyticsResponse analytics) {
        return analytics.weekly().isEmpty() ? 0 : analytics.weekly().get(0).counts()[analytics.weeks().size() - 1];
    }

    private static DepartmentOnTime department(AnalyticsResponse analytics, String code) {
        return analytics.departments().stream().filter(item -> item.code().equals(code)).findFirst()
                .orElse(new DepartmentOnTime(code, code, ru.mtuci.appeals.api.ApiModels.Rate.of(0, 0), 0, 0));
    }
}
