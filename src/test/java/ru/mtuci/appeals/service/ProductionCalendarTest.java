package ru.mtuci.appeals.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.mtuci.appeals.service.ProductionCalendar.DayKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.mtuci.appeals.domain.DeadlineCounting.CALENDAR_DAYS;
import static ru.mtuci.appeals.domain.DeadlineCounting.CALENDAR_EXCEPT_HOLIDAYS;
import static ru.mtuci.appeals.domain.DeadlineCounting.WORKING_DAYS;

/**
 * Счёт сроков по настоящим файлам календаря из каталога calendar/. Самое трудное место —
 * новогодние праздники: обращение, поступившее 25.12.2026, считается через 31.12
 * (перенесённый выходной) и праздники 1–8 января 2027 года.
 */
class ProductionCalendarTest {

    private static ProductionCalendar calendar;

    @BeforeAll
    static void loadCalendarFiles() throws IOException {
        Map<LocalDate, DayKind> days = new HashMap<>();
        for (int year : new int[]{2026, 2027}) {
            List<String> lines = Files.readAllLines(Path.of("calendar", "production-calendar-" + year + ".csv"));
            lines.stream().skip(1).map(line -> line.split(","))
                    .forEach(parts -> days.put(LocalDate.parse(parts[0]), DayKind.valueOf(parts[1])));
        }
        calendar = new ProductionCalendar(days);
    }

    @Test
    void fifteenWorkingDaysSkipNewYearHolidays() {
        // 4015-1, ст. 6.2: 28, 29, 30 декабря, затем с 11 января — 15-й рабочий день 26 января
        assertThat(calendar.deadline(date("2026-12-25"), 15, WORKING_DAYS)).isEqualTo(date("2027-01-26"));
    }

    @Test
    void calendarDaysEndingOnDayOffMoveToNextWorkday() {
        // 123-ФЗ, ст. 16 ч. 2 п. 2: 30-й день — воскресенье 24 января, срок — понедельник 25-го
        assertThat(calendar.deadline(date("2026-12-25"), 30, CALENDAR_DAYS)).isEqualTo(date("2027-01-25"));
    }

    @Test
    void osagoCountsWeekendsButNotHolidays() {
        // 40-ФЗ, ст. 12 п. 21: выходные считаются (в том числе 31.12), праздники 1–8 января — нет
        assertThat(calendar.deadline(date("2026-12-25"), 20, CALENDAR_EXCEPT_HOLIDAYS)).isEqualTo(date("2027-01-22"));
    }

    @Test
    void transferredDaysFollowGovernmentDecree() {
        // постановление № 1187: суббота 20.02.2027 рабочая, понедельник 22.02 выходной
        assertThat(calendar.kind(date("2027-02-20"))).isEqualTo(DayKind.WORKDAY);
        assertThat(calendar.kind(date("2027-02-22"))).isEqualTo(DayKind.WEEKEND);
        assertThat(calendar.kind(date("2027-02-23"))).isEqualTo(DayKind.HOLIDAY);
        assertThat(calendar.deadline(date("2027-02-19"), 2, WORKING_DAYS)).isEqualTo(date("2027-02-24"));
    }

    @Test
    void yearHasOfficialNumberOfWorkingDays() {
        // сверка файла с официальным итогом: 247 рабочих дней
        long working = date("2027-01-01").datesUntil(date("2028-01-01")).filter(calendar::isWorkday).count();
        assertThat(working).isEqualTo(247);
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
