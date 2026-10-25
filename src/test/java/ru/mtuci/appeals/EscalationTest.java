package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ExtendDeadlineRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.EscalationLevel;
import ru.mtuci.appeals.service.EscalationService;
import ru.mtuci.appeals.service.ProductionCalendar;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Эскалация по сроку закона (решение 14): за 2 рабочих дня до срока ставится «срок истекает»,
 * после срока «срок нарушен». Проход планировщика вызывается с нужной датой.
 */
class EscalationTest extends IntegrationTest {

    @Autowired
    private EscalationService escalation;

    @Autowired
    private ProductionCalendar calendar;

    @Test
    void deadlineApproachingRaisesPriorityOnce() {
        UUID appeal = routedLoginProblem();
        LocalDate deadline = getAppeal(appeal).legalDeadline();

        // за 3 рабочих дня: ещё рано
        escalation.scan(workingDaysBefore(deadline, 3));
        assertThat(getAppeal(appeal).escalationLevel()).isEqualTo(EscalationLevel.NONE);

        // за 2 рабочих дня: предупреждение, повторный проход ничего не дублирует
        escalation.scan(workingDaysBefore(deadline, 2));
        escalation.scan(workingDaysBefore(deadline, 2));

        AppealDetailResponse escalated = getAppeal(appeal);
        assertThat(escalated.escalationLevel()).isEqualTo(EscalationLevel.AT_RISK);
        assertThat(escalated.priority()).isEqualTo(AppealPriority.HIGH);
        assertThat(escalations(escalated)).hasSize(1);
        assertThat(escalations(escalated).get(0).description()).contains("осталось 2 раб. дн.");
    }

    @Test
    void missedDeadlineIsBreachedWithCriticalPriority() {
        UUID appeal = routedLoginProblem();
        LocalDate deadline = getAppeal(appeal).legalDeadline();

        escalation.scan(deadline.plusDays(1));

        AppealDetailResponse breached = getAppeal(appeal);
        assertThat(breached.escalationLevel()).isEqualTo(EscalationLevel.BREACHED);
        assertThat(breached.priority()).isEqualTo(AppealPriority.CRITICAL);
        assertThat(escalations(breached).get(0).description()).contains("нарушен");
    }

    @Test
    void resolvedAppealIsNotEscalated() {
        UUID appeal = routedLoginProblem();
        LocalDate deadline = getAppeal(appeal).legalDeadline();
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/resolve",
                new ResolveAppealRequest(AppealOutcome.EXPLAINED, "Код приходит с задержкой до 5 минут, объяснили порядок входа.", null, null),
                String.class, appeal).getStatusCode()).isEqualTo(HttpStatus.OK);

        escalation.scan(deadline.plusDays(1));

        assertThat(getAppeal(appeal).escalationLevel()).isEqualTo(EscalationLevel.NONE);
    }

    @Test
    void extensionClearsEscalation() {
        UUID appeal = routedLoginProblem();
        LocalDate deadline = getAppeal(appeal).legalDeadline();
        LocalDate warningDay = workingDaysBefore(deadline, 1);
        escalation.scan(warningDay);
        assertThat(getAppeal(appeal).escalationLevel()).isEqualTo(EscalationLevel.AT_RISK);

        as(SUPPORT_SUPERVISOR).postForObject("/api/appeals/{id}/extend-deadline",
                new ExtendDeadlineRequest(10, "Нужна выгрузка журнала входов от оператора SMS"),
                AppealDetailResponse.class, appeal);
        escalation.scan(warningDay);

        // новый срок далеко: снова предупреждать не о чем
        assertThat(getAppeal(appeal).escalationLevel()).isEqualTo(EscalationLevel.NONE);
    }

    private UUID routedLoginProblem() {
        UUID id = createAppeal(loginProblem()).id();
        awaitRouted(id);
        return id;
    }

    /** Рабочий день, за n рабочих дней до срока. */
    private LocalDate workingDaysBefore(LocalDate deadline, int n) {
        LocalDate day = deadline;
        for (int left = n; left > 0; ) {
            day = day.minusDays(1);
            if (calendar.isWorkday(day)) {
                left--;
            }
        }
        return day;
    }

    private static java.util.List<HistoryResponse> escalations(AppealDetailResponse appeal) {
        return appeal.history().stream().filter(item -> item.action().equals("ESCALATED")).toList();
    }
}
