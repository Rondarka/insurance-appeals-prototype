package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ClientResponse;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;

import java.time.LocalDate;
import java.util.Arrays;
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
        ClientResponse[] clients = rest.getForObject("/api/clients", ClientResponse[].class);
        ClientResponse client = Arrays.stream(clients)
                .filter(found -> found.id().equals(CLIENT))
                .findFirst().orElseThrow();

        // все договоры клиента, включая истёкший и досрочно прекращённый
        assertThat(client.contracts())
                .extracting(ContractResponse::productCode, ContractResponse::status, ContractResponse::terminatedOn)
                .containsExactlyInAnyOrder(
                        tuple("MORTGAGE", "ACTIVE", null),
                        tuple("CASCO", "ACTIVE", null),
                        tuple("OSAGO", "EXPIRED", null),
                        tuple("PROPERTY", "TERMINATED", LocalDate.of(2026, 8, 1)));
    }

    @Test
    void rejectsContractUnknownToAccountingSystem() {
        int before = appealsOfClient(CLIENT);

        ResponseEntity<ErrorResponse> response = post(claimOn(UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Договор не найден");
        assertThat(appealsOfClient(CLIENT)).isEqualTo(before);
    }

    @Test
    void rejectsContractThatBelongsToAnotherClient() {
        int before = appealsOfClient(CLIENT);

        ResponseEntity<ErrorResponse> response = post(claimOn(OTHER_CLIENTS_CONTRACT));

        // ответ тот же, что для несуществующего договора: не раскрываем, что договор есть
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("не принадлежит");
        assertThat(appealsOfClient(CLIENT)).isEqualTo(before);
    }

    @Test
    void accountingSystemFailureIsServiceUnavailableNotClientError() {
        int before = appealsOfClient(CLIENT);

        ResponseEntity<ErrorResponse> response = post(claimOn(FAILING_CONTRACT));

        // Пока без деградированного режима: регистрация не проходит, но это сбой
        // источника (503), а не ошибка клиента (400). Решение 9 изменит это поведение.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(appealsOfClient(CLIENT)).isEqualTo(before);
    }

    private static CreateAppealRequest claimOn(UUID contractId) {
        return new CreateAppealRequest(
                CLIENT, contractId, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "Москва"),
                "ДТП", "Обращение по договору " + contractId);
    }

    private ResponseEntity<ErrorResponse> post(CreateAppealRequest request) {
        return rest.postForEntity("/api/appeals", request, ErrorResponse.class);
    }
}
