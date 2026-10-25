package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.FormAppealType;
import ru.mtuci.appeals.api.ApiModels.FormCategory;
import ru.mtuci.appeals.api.ApiModels.FormSchemaResponse;
import ru.mtuci.appeals.api.ApiModels.MeResponse;
import ru.mtuci.appeals.domain.ContractRole;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Договоры живут во внешней учётной системе (заглушка WireMock по docs/contracts-api.yaml),
 * обращение хранит снимок договора на момент регистрации.
 */
class ContractIntegrationTest extends IntegrationTest {

    private static final UUID OTHER_CLIENTS_CONTRACT = UUID.fromString("29999999-9999-9999-9999-999999999999");
    private static final UUID FAILING_CONTRACT = UUID.fromString("25000000-0000-0000-0000-000000000500");
    private static final UUID LIFE_CONTRACT = UUID.fromString("27777777-7777-7777-7777-777777777777");

    private static CreateAppealRequest terminationOf(UUID contract) {
        return new CreateAppealRequest(contract, null, null, "POLICY", "TERMINATION", Map.of(),
                "Расторгнуть договор страхования жизни", "Прошу расторгнуть договор с ближайшей даты.");
    }

    @Test
    void appealStoresSnapshotOfContractFromAccountingSystem() {
        AppealDetailResponse appeal = createAppeal(cascoClaim());

        ContractResponse contract = getAppeal(appeal.id()).contract();
        assertThat(contract.id()).isEqualTo(CASCO_CONTRACT);
        assertThat(contract.policyNumber()).isEqualTo("КАСКО-2026-004821");
        assertThat(contract.productCode()).isEqualTo("CASCO");
        assertThat(contract.insuranceType()).isEqualTo("КАСКО");
        assertThat(contract.validFrom()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(contract.validTo()).isEqualTo(LocalDate.of(2027, 3, 14));
        assertThat(contract.status()).isEqualTo("ACTIVE");
        // номер полиса подставлен сервером из договора, а не взят у клиента
        assertThat(appeal.details()).containsEntry("policyNumber", "КАСКО-2026-004821");
    }

    @Test
    void clientContractsComeFromAccountingSystem() {
        // договоры клиента из токена: без идентификатора в запросе
        MeResponse me = as(ANNA).getForObject("/api/me", MeResponse.class);

        // все договоры клиента, включая истёкший, досрочно прекращённый и чужой, где она застрахована
        assertThat(me.contracts())
                .extracting(ContractResponse::productCode, ContractResponse::status, ContractResponse::terminatedOn,
                        ContractResponse::clientRole)
                .containsExactlyInAnyOrder(
                        tuple("MORTGAGE", "ACTIVE", null, ContractRole.POLICYHOLDER),
                        tuple("CASCO", "ACTIVE", null, ContractRole.POLICYHOLDER),
                        tuple("OSAGO", "EXPIRED", null, ContractRole.POLICYHOLDER),
                        tuple("PROPERTY", "TERMINATED", LocalDate.of(2026, 8, 1), ContractRole.POLICYHOLDER),
                        tuple("OSAGO", "ACTIVE", null, ContractRole.POLICYHOLDER),
                        tuple("CASCO", "ACTIVE", null, ContractRole.POLICYHOLDER),
                        tuple("LIFE", "ACTIVE", null, ContractRole.INSURED));
    }

    @Test
    void insuredPersonReportsClaimButCannotTerminateContract() {
        // договор страхования жизни оформил Пётр, Анна в нём застрахованное лицо
        FormSchemaResponse schema = as(ANNA).getForObject(
                "/api/form-schema?productCode=LIFE&clientRole=INSURED", FormSchemaResponse.class);
        assertThat(schema.categories()).flatExtracting(FormCategory::types).extracting(FormAppealType::code)
                .contains("HEALTH", "COPY")
                .doesNotContain("TERMINATION", "DATA_CHANGE", "REFUND");

        int before = appealsOf(ANNA);
        ResponseEntity<ErrorResponse> termination = as(ANNA).postForEntity("/api/appeals",
                terminationOf(LIFE_CONTRACT), ErrorResponse.class);
        assertThat(termination.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(termination.getBody().message()).contains("только страхователь", "застрахованное лицо");
        assertThat(appealsOf(ANNA)).isEqualTo(before);

        AppealDetailResponse claim = createAppeal(new CreateAppealRequest(
                LIFE_CONTRACT, null, null, "CLAIM", "HEALTH",
                Map.of("incidentDate", "2026-09-20", "incidentPlace", "г. Москва, каток в парке"),
                "Перелом руки на катке", "Упала на катке, перелом лучевой кости, выписка из травмпункта есть."));
        assertThat(claim.contract().clientRole()).isEqualTo(ContractRole.INSURED);
        // сотрудник сразу видит, кем клиент приходится договору
        assertThat(claim.checks()).filteredOn(check -> check.code().equals("CLIENT_ROLE"))
                .singleElement()
                .satisfies(check -> assertThat(check.message()).contains("застрахованное лицо"));
    }

    @Test
    void policyholderTerminatesContractOfInsuredPerson() {
        ResponseEntity<AppealDetailResponse> termination = as(PETR).postForEntity("/api/appeals",
                terminationOf(LIFE_CONTRACT), AppealDetailResponse.class);

        assertThat(termination.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(termination.getBody().contract().clientRole()).isEqualTo(ContractRole.POLICYHOLDER);
    }

    @Test
    void rejectsContractUnknownToAccountingSystem() {
        int before = appealsOf(ANNA);

        ResponseEntity<ErrorResponse> response = post(claimOn(UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Договор не найден");
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    @Test
    void rejectsContractThatBelongsToAnotherClient() {
        int before = appealsOf(ANNA);

        ResponseEntity<ErrorResponse> response = post(claimOn(OTHER_CLIENTS_CONTRACT));

        // ответ тот же, что для несуществующего договора: не раскрываем, что договор есть
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("не принадлежит");
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    @Test
    void accountingSystemFailureIsServiceUnavailableNotClientError() {
        int before = appealsOf(ANNA);

        ResponseEntity<ErrorResponse> response = post(claimOn(FAILING_CONTRACT));

        // Пока без деградированного режима: регистрация не проходит, но это сбой
        // источника (503), а не ошибка клиента (400). Решение 9 изменит это поведение.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    private static CreateAppealRequest claimOn(UUID contractId) {
        return new CreateAppealRequest(
                contractId, null, null, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "Москва"),
                "ДТП", "Обращение по договору " + contractId);
    }

    private ResponseEntity<ErrorResponse> post(CreateAppealRequest request) {
        return as(ANNA).postForEntity("/api/appeals", request, ErrorResponse.class);
    }
}
