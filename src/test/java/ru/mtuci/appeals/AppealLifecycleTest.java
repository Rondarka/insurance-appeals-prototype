package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AddMessageRequest;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.domain.AppealOutcome;
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
    void historyRemembersStatusForClientTimeline() {
        UUID appeal = routedClaim();
        changeStatus(appeal, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);
        changeStatus(appeal, AppealStatus.WAITING_CUSTOMER, AppealDetailResponse.class);
        as(ANNA).postForObject("/api/appeals/{id}/messages",
                new AddMessageRequest("Фото повреждений приложила"), AppealDetailResponse.class, appeal);
        resolve(appeal, AppealOutcome.SATISFIED, "Выплата 52 000 ₽ направлена на ваш счёт по условиям договора.",
                AppealDetailResponse.class);

        // по этим статусам кабинет клиента ставит даты шагов «принято», «в работе», «ответ дан»
        assertThat(getAppeal(appeal).history()).extracting(HistoryResponse::statusAfter).containsSubsequence(
                AppealStatus.PENDING_ROUTING, AppealStatus.ROUTED, AppealStatus.IN_PROGRESS,
                AppealStatus.WAITING_CUSTOMER, AppealStatus.IN_PROGRESS, AppealStatus.RESOLVED);
    }

    @Test
    void closedAppealAcceptsNoMessages() {
        UUID appeal = routedClaim();
        changeStatus(appeal, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);
        resolve(appeal, AppealOutcome.EXPLAINED, "Разъяснили порядок выплаты по договору КАСКО.", AppealDetailResponse.class);
        changeStatus(appeal, AppealStatus.CLOSED, AppealDetailResponse.class);

        ResponseEntity<ErrorResponse> reply = as(ANNA).postForEntity("/api/appeals/{id}/messages",
                new AddMessageRequest("А можно ещё вопрос?"), ErrorResponse.class, appeal);

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAppeal(appeal).nextStatuses()).isEmpty();
    }

    @Test
    void closingKeepsOutcomeAndReopeningDropsIt() {
        UUID closed = routedClaim();
        changeStatus(closed, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);
        resolve(closed, AppealOutcome.SATISFIED, "Выплата 40 000 ₽ направлена на ваш счёт по условиям договора.", AppealDetailResponse.class);
        changeStatus(closed, AppealStatus.CLOSED, AppealDetailResponse.class);

        // закрытие — не пересмотр: итог остаётся результатом рассмотрения
        assertThat(getAppeal(closed).outcome()).isEqualTo(AppealOutcome.SATISFIED);
        assertThat(getAppeal(closed).resolvedAt()).isNotNull();

        UUID reopened = routedClaim();
        changeStatus(reopened, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);
        resolve(reopened, AppealOutcome.REJECTED, "Событие не является страховым случаем по условиям договора.", AppealDetailResponse.class);
        changeStatus(reopened, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);

        // клиент не согласен — прежний итог больше не действует
        assertThat(getAppeal(reopened).outcome()).isNull();
    }

    @Test
    void appealIsResolvedOnlyByAnswerToClient() {
        UUID appeal = routedClaim();
        changeStatus(appeal, AppealStatus.IN_PROGRESS, AppealDetailResponse.class);

        // просто сменить статус на «решено» нельзя, и ответ должен быть по существу
        ResponseEntity<ErrorResponse> bareStatus = changeStatus(appeal, AppealStatus.RESOLVED, ErrorResponse.class);
        ResponseEntity<ErrorResponse> emptyAnswer = resolve(appeal, AppealOutcome.SATISFIED, "Решено", ErrorResponse.class);
        assertThat(bareStatus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(emptyAnswer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAppeal(appeal).status()).isEqualTo(AppealStatus.IN_PROGRESS);

        String answer = "Ущерб признан страховым случаем, выплата 85 000 ₽ направлена на ваш счёт.";
        resolve(appeal, AppealOutcome.SATISFIED, answer, AppealDetailResponse.class);

        // клиент видит и ответ в переписке, и итог
        AppealDetailResponse seenByClient = getAppeal(appeal);
        assertThat(seenByClient.status()).isEqualTo(AppealStatus.RESOLVED);
        assertThat(seenByClient.outcome()).isEqualTo(AppealOutcome.SATISFIED);
        assertThat(seenByClient.messages()).extracting(message -> message.body()).contains(answer);
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

    private <T> ResponseEntity<T> resolve(UUID appeal, AppealOutcome outcome, String answer, Class<T> type) {
        return as(CLAIMS_SPECIALIST).postForEntity("/api/appeals/{id}/resolve",
                new ResolveAppealRequest(outcome, answer, null, null), type, appeal);
    }

    private <T> ResponseEntity<T> changeStatus(UUID appeal, AppealStatus status, Class<T> type) {
        return as(CLAIMS_SPECIALIST).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(status), type, appeal);
    }
}
