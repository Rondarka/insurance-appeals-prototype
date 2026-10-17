package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.mtuci.appeals.TestIdentityProvider.TestUser;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.ExtendDeadlineRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.MessageResponse;
import ru.mtuci.appeals.service.ProductionCalendar;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Продление срока по закону (Закон РФ № 4015-1, ст. 6.2 п. 5): решение руководителя,
 * не больше 10 рабочих дней, с уведомлением клиента и обоснованием.
 */
class DeadlineExtensionTest extends IntegrationTest {

    private static final String REASON = "Запрошена справка у банка-кредитора, ответ ожидается до конца месяца";

    @Autowired
    private ProductionCalendar calendar;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void supervisorExtendsDeadlineAndClientIsNotified() {
        UUID appeal = routedLoginProblem();
        LocalDate before = getAppeal(appeal).legalDeadline();

        AppealDetailResponse extended = extend(SUPPORT_SUPERVISOR, appeal, 10, AppealDetailResponse.class).getBody();

        // новый срок — 10 рабочих дней от прежнего по производственному календарю
        LocalDate expected = calendar.addWorkingDays(before, 10);
        assertThat(extended.legalDeadline()).isEqualTo(expected);
        assertThat(extended.deadlineExtendedDays()).isEqualTo(10);
        assertThat(extended.deadlineExtensionReason()).isEqualTo(REASON);
        assertThat(extended.history()).extracting(HistoryResponse::action).contains("DEADLINE_EXTENDED");

        // клиент видит новый срок и причину в переписке
        AppealDetailResponse seenByClient = getAppeal(appeal);
        MessageResponse notice = seenByClient.messages().get(seenByClient.messages().size() - 1);
        assertThat(seenByClient.legalDeadline()).isEqualTo(expected);
        assertThat(notice.body())
                .contains(expected.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                .contains(REASON);
    }

    @Test
    void onlySupervisorOfTheDepartmentExtends() {
        UUID appeal = routedLoginProblem();

        assertThat(extend(SUPPORT_SPECIALIST, appeal, 5, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(extend(ANNA, appeal, 5, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // руководитель другого отдела обращения не видит
        assertThat(extend(CLAIMS_SUPERVISOR, appeal, 5, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getAppeal(appeal).deadlineExtendedDays()).isZero();
    }

    @Test
    void extensionIsLimitedByLaw() {
        UUID appeal = routedLoginProblem();

        // больше 10 рабочих дней нельзя, второй раз — тоже
        ResponseEntity<ErrorResponse> tooLong = extend(SUPPORT_SUPERVISOR, appeal, 11, ErrorResponse.class);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().message()).contains("1–10");

        extend(SUPPORT_SUPERVISOR, appeal, 3, AppealDetailResponse.class);
        assertThat(extend(SUPPORT_SUPERVISOR, appeal, 3, ErrorResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAppeal(appeal).deadlineExtendedDays()).isEqualTo(3);
    }

    @Test
    void normWithoutExtensionCannotBeExtended() {
        // страховой случай по КАСКО — срок по правилам страхования, продление не предусмотрено
        UUID claim = createAppeal(cascoClaim()).id();
        awaitRouted(claim);

        ResponseEntity<ErrorResponse> response = extend(CLAIMS_SUPERVISOR, claim, 5, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("продления не предусматривает");
    }

    @Test
    void expiredDeadlineCannotBeExtendedRetroactively() {
        UUID appeal = routedLoginProblem();
        jdbc.sql("update appeal set legal_deadline = current_date - 1 where id = :id").param("id", appeal).update();

        ResponseEntity<ErrorResponse> response = extend(SUPPORT_SUPERVISOR, appeal, 5, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("задним числом");
    }

    private UUID routedLoginProblem() {
        UUID id = createAppeal(loginProblem()).id();
        awaitRouted(id);
        return id;
    }

    private <T> ResponseEntity<T> extend(TestUser user, UUID appeal, int days, Class<T> type) {
        return as(user).postForEntity("/api/appeals/{id}/extend-deadline",
                new ExtendDeadlineRequest(days, REASON), type, appeal);
    }
}
