package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.TestIdentityProvider.TestUser;
import ru.mtuci.appeals.api.ApiModels.CreateTransferRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.ReviewTransferRequest;
import ru.mtuci.appeals.api.ApiModels.TransferRequestResponse;
import ru.mtuci.appeals.domain.TransferRequestStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Согласуемая передача: ответственность за обращение не снимается односторонне.
 * Отдел меняется только после согласия руководителя принимающего отдела.
 */
class TransferApprovalTest extends IntegrationTest {

    @Test
    void transferTakesEffectOnlyAfterTargetSupervisorApproves() {
        UUID appeal = routedToSupport();

        TransferRequestResponse transfer = requestTransferToClaims(appeal);
        assertThat(transfer.status()).isEqualTo(TransferRequestStatus.PENDING);
        assertThat(departmentOf(appeal)).as("до решения отдел прежний").isEqualTo("SUPPORT");

        ResponseEntity<TransferRequestResponse> review = review(transfer.id(), CLAIMS_SUPERVISOR, true,
                TransferRequestResponse.class);

        assertThat(review.getBody().status()).isEqualTo(TransferRequestStatus.APPROVED);
        assertThat(departmentOf(appeal)).isEqualTo("CLAIMS");
    }

    @Test
    void rejectedTransferLeavesAppealInSourceDepartment() {
        UUID appeal = routedToSupport();
        TransferRequestResponse transfer = requestTransferToClaims(appeal);

        ResponseEntity<TransferRequestResponse> review = review(transfer.id(), CLAIMS_SUPERVISOR, false,
                TransferRequestResponse.class);

        assertThat(review.getBody().status()).isEqualTo(TransferRequestStatus.REJECTED);
        assertThat(departmentOf(appeal)).isEqualTo("SUPPORT");
    }

    @Test
    void onlySupervisorOfTargetDepartmentCanReview() {
        UUID appeal = routedToSupport();
        TransferRequestResponse transfer = requestTransferToClaims(appeal);

        // специалист принимающего отдела — не руководитель
        ResponseEntity<ErrorResponse> bySpecialist = review(transfer.id(), CLAIMS_SPECIALIST, true,
                ErrorResponse.class);
        // руководитель, но исходного отдела, а не принимающего
        ResponseEntity<ErrorResponse> byOtherSupervisor = review(transfer.id(), SUPPORT_SUPERVISOR, true,
                ErrorResponse.class);

        // роль и подразделение берутся из токена: обоим отказано в доступе
        assertThat(bySpecialist.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byOtherSupervisor.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(departmentOf(appeal)).isEqualTo("SUPPORT");
    }

    private UUID routedToSupport() {
        UUID id = createAppeal(loginProblem()).id();
        assertThat(awaitRouted(id).department().code()).isEqualTo("SUPPORT");
        return id;
    }

    private TransferRequestResponse requestTransferToClaims(UUID appeal) {
        ResponseEntity<TransferRequestResponse> response = as(SUPPORT_SPECIALIST).postForEntity(
                "/api/appeals/{id}/transfer-requests",
                new CreateTransferRequest("CLAIMS", "Клиент сообщил о страховом случае"),
                TransferRequestResponse.class, appeal);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private <T> ResponseEntity<T> review(UUID transfer, TestUser reviewer, boolean approved, Class<T> type) {
        return as(reviewer).postForEntity("/api/transfer-requests/{id}/review",
                new ReviewTransferRequest(approved, approved ? "Принимаем" : "Не наш профиль"),
                type, transfer);
    }

    private String departmentOf(UUID appeal) {
        return getAppeal(appeal).department().code();
    }
}
