package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.CalendarController.CalendarDay;
import ru.mtuci.appeals.domain.DeadlineCounting;
import ru.mtuci.appeals.service.ProductionCalendar;
import ru.mtuci.appeals.service.ProductionCalendar.DayKind;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нормативный срок назначается при регистрации по справочнику (решения 20, 21):
 * зависит от типа обращения, продукта договора и — у претензии — от даты нарушения.
 */
class DeadlineNormTest extends IntegrationTest {

    private static final UUID OSAGO_CONTRACT = UUID.fromString("23333333-3333-3333-3333-333333333333");

    @Autowired
    private ProductionCalendar calendar;

    @Test
    void generalAppealGetsFifteenWorkingDaysByInsuranceLaw() {
        AppealDetailResponse appeal = createAppeal(loginProblem());

        assertThat(appeal.norm().code()).isEqualTo("GENERAL");
        assertThat(appeal.norm().legalBasis()).contains("4015-1, ст. 6.2");
        assertThat(appeal.legalDeadline()).isEqualTo(calendar.deadline(today(), 15, DeadlineCounting.WORKING_DAYS));
    }

    @Test
    void osagoClaimCountsCalendarDaysWithoutHolidays() {
        AppealDetailResponse appeal = createAppeal(new CreateAppealRequest(
                OSAGO_CONTRACT, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-03-01", "incidentPlace", "Москва"),
                "ДТП по ОСАГО", "Прошу возместить ущерб по ОСАГО."));

        assertThat(appeal.norm().code()).isEqualTo("OSAGO_CLAIM");
        assertThat(appeal.legalDeadline())
                .isEqualTo(calendar.deadline(today(), 20, DeadlineCounting.CALENDAR_EXCEPT_HOLIDAYS));
    }

    @Test
    void cascoClaimFollowsInsuranceRules() {
        // ст. 6.2 на заявления о выплате не распространяется — срок по правилам страхования
        assertThat(createAppeal(cascoClaim()).norm().code()).isEqualTo("INSURANCE_RULES");
    }

    @Test
    void pretensionTermDependsOnViolationDate() {
        AppealDetailResponse recent = createAppeal(pretension(today().minusDays(10)));
        AppealDetailResponse old = createAppeal(pretension(today().minusDays(200)));

        // 123-ФЗ, ст. 16 ч. 2: не старше 180 дней — 15 рабочих, иначе 30 календарных
        assertThat(recent.norm().code()).isEqualTo("PRETENSION_FAST");
        assertThat(recent.legalDeadline()).isEqualTo(calendar.deadline(today(), 15, DeadlineCounting.WORKING_DAYS));
        assertThat(old.norm().code()).isEqualTo("PRETENSION");
        assertThat(old.legalDeadline()).isEqualTo(calendar.deadline(today(), 30, DeadlineCounting.CALENDAR_DAYS));
    }

    @Test
    void calendarApiShowsTransferredDaysOff() {
        CalendarDay[] days = as(CLAIMS_SPECIALIST).getForObject(
                "/api/calendar?from=2027-02-19&to=2027-02-23", CalendarDay[].class);

        // постановление № 1187: суббота 20.02 рабочая, понедельник 22.02 выходной
        assertThat(days).extracting(CalendarDay::kind).containsExactly(
                DayKind.WORKDAY, DayKind.WORKDAY, DayKind.WEEKEND, DayKind.WEEKEND, DayKind.HOLIDAY);
    }

    private static CreateAppealRequest pretension(LocalDate violation) {
        return new CreateAppealRequest(
                CASCO_CONTRACT, "COMPLAINT", "PRETENSION",
                Map.of("violationDate", violation.toString(), "claimAmount", "85000",
                        "desiredOutcome", "Выплатить возмещение полностью"),
                "Не согласна с суммой выплаты", "Выплата занижена, прошу пересчитать.");
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Europe/Moscow"));
    }
}
