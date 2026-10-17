package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.CheckResponse;
import ru.mtuci.appeals.api.ApiModels.ContractTermsResponse;
import ru.mtuci.appeals.api.ApiModels.CoverageResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.RelatedAppealResponse;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * На чём сотрудник основывает решение: условия договора из учётной системы, проверки,
 * которые подсистема делает за него (решение 21), и история обращений клиента.
 */
class AppealInsightTest extends IntegrationTest {

    // договор имущества Анны: 10.01.2026 – 09.01.2027, прекращён досрочно 01.08.2026
    private static final UUID PROPERTY_CONTRACT = UUID.fromString("24444444-4444-4444-4444-444444444444");

    @Test
    void incidentAfterEarlyTerminationIsFlagged() {
        AppealDetailResponse appeal = createAppeal(propertyClaim("2026-10-05"));

        assertThat(check(appeal, "COVERAGE").level()).isEqualTo("WARNING");
        assertThat(check(appeal, "COVERAGE").message()).contains("после досрочного прекращения договора 01.08.2026");
    }

    @Test
    void incidentWithinContractPeriodIsCovered() {
        AppealDetailResponse appeal = createAppeal(propertyClaim("2026-05-15"));

        assertThat(check(appeal, "COVERAGE").level()).isEqualTo("OK");
    }

    @Test
    void employeeGetsContractTermsFromAccountingSystem() {
        UUID appeal = createAppeal(cascoClaim()).id();
        awaitRouted(appeal);

        ContractTermsResponse terms = as(CLAIMS_SPECIALIST).getForObject(
                "/api/appeals/{id}/contract", ContractTermsResponse.class, appeal);

        assertThat(terms.insuredSum()).isEqualByComparingTo(new BigDecimal("3200000"));
        assertThat(terms.deductible()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(terms.coverages()).extracting(CoverageResponse::name).contains("Ущерб", "Угон и хищение");
        // условия чужого обращения не отдаются, как и само обращение
        assertThat(as(PETR).getForEntity("/api/appeals/{id}/contract", String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void repeatedAppealIsFlaggedAndClientHistoryIsShown() {
        AppealDetailResponse first = createAppeal(cascoClaim());
        AppealDetailResponse second = createAppeal(cascoClaim());
        awaitRouted(second.id());

        assertThat(check(getAppeal(second.id()), "REPEATED").level()).isEqualTo("WARNING");
        RelatedAppealResponse[] history = as(CLAIMS_SPECIALIST).getForObject(
                "/api/appeals/{id}/related", RelatedAppealResponse[].class, second.id());
        assertThat(history).filteredOn(item -> item.id().equals(first.id()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.sameContract()).isTrue();
                    assertThat(item.canOpen()).isTrue();
                });
    }

    private static CheckResponse check(AppealDetailResponse appeal, String code) {
        return appeal.checks().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }

    private static CreateAppealRequest propertyClaim(String incidentDate) {
        return new CreateAppealRequest(
                PROPERTY_CONTRACT, "CLAIM", "PROPERTY",
                Map.of("incidentDate", incidentDate, "incidentPlace", "д. Берёзки, ул. Садовая, д. 7"),
                "Протечка кровли", "После ливня протекла крыша над спальней.");
    }
}
