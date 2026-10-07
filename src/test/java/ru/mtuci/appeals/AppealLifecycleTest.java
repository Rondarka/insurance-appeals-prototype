package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AddMessageRequest;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.domain.AppealStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Жизненный цикл обращения: сотрудник переводит его только по разрешённым шагам,
 * ответ клиента на запрос сам возвращает обращение в работу.
 */
class AppealLifecycleTest extends IntegrationTest {

    @Test
    void employeeCannotSkipSteps() {
        UUID appeal = routedClaim();

        // назначено отделу → сразу «решено» нельзя: сначала в работу
        ResponseEntity<ErrorResponse> skip = changeStatus(appeal, AppealStatus.RESOLVED, ErrorResponse.class);

        assertThat(skip.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAppeal(appeal).status()).isEqualTo(AppealStatus.ROUTED);
        assertThat(getAppeal(appeal).nextStatuses()).containsExactly(AppealStatus.IN_PROGRESS);
    }

    @Test
    void clientReplyReturnsAppealToWork() {
        UUID appeal = routedClaim();
        changeStatus(appeal, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);
        changeStatus(appeal, AppealStatus.WAITING_CUSTOMER, AppealDetailResponse.class);

        AppealDetailResponse answered = as(ANNA).postForObject("/api/appeals/{id}/messages",
                new AddMessageRequest("Акт управляющей компании приложила"), AppealDetailResponse.class, appeal);

        assertThat(answered.status()).isEqualTo(AppealStatus.IN_PROGRESS);
        assertThat(answered.nextStatuses()).containsExactly(AppealStatus.WAITING_CUSTOMER, AppealStatus.RESOLVED);
    }

    @Test
    void closedAppealAcceptsNoMessages() {
        UUID appeal = routedClaim();
        changeStatus(appeal, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);
        changeStatus(appeal, AppealStatus.RESOLVED, AppealDetailResponse.class);
        changeStatus(appeal, AppealStatus.CLOSED, AppealDetailResponse.class);

        ResponseEntity<ErrorResponse> reply = as(ANNA).postForEntity("/api/appeals/{id}/messages",
                new AddMessageRequest("А можно ещё вопрос?"), ErrorResponse.class, appeal);

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAppeal(appeal).nextStatuses()).isEmpty();
    }

    @Test
    void adminSeesAllDepartmentsButDoesNotWork() {
        UUID claim = routedClaim();
        UUID login = createAppeal(loginProblem()).id();
        awaitRouted(login);

        AppealSummaryResponse[] all = as(ADMIN).getForObject("/api/appeals", AppealSummaryResponse[].class);
        AppealSummaryResponse[] support = as(ADMIN).getForObject(
                "/api/appeals?departmentCode=SUPPORT", AppealSummaryResponse[].class);

        assertThat(all).extracting(AppealSummaryResponse::id).contains(claim, login);
        assertThat(support).extracting(AppealSummaryResponse::id).contains(login).doesNotContain(claim);
        assertThat(as(ADMIN).getForEntity("/api/appeals/{id}", String.class, claim).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // смотреть может всё, работать с обращениями — нет
        assertThat(as(ADMIN).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), String.class, claim).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID routedClaim() {
        UUID id = createAppeal(cascoClaim()).id();
        awaitRouted(id);
        return id;
    }

    private <T> ResponseEntity<T> changeStatus(UUID appeal, AppealStatus status, Class<T> type) {
        return as(CLAIMS_SPECIALIST).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(status), type, appeal);
    }
}
