package ru.mtuci.appeals.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.mtuci.appeals.domain.DeadlineCounting;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Производственный календарь и счёт сроков (решение 13). Календарь подсистема не ведёт:
 * таблицу заполняет {@link CalendarImporter} из файлов утверждённого календаря. Дни вне
 * таблицы считаются по обычной неделе.
 */
@Component
public class ProductionCalendar {

    public enum DayKind { WORKDAY, WEEKEND, HOLIDAY }

    private final JdbcClient jdbc;
    private volatile Map<LocalDate, DayKind> exceptions = Map.of();

    @Autowired
    public ProductionCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Для модульных тестов: календарь без базы. */
    ProductionCalendar(Map<LocalDate, DayKind> exceptions) {
        this.jdbc = null;
        this.exceptions = Map.copyOf(exceptions);
    }

    public void reload() {
        exceptions = jdbc.sql("select day, kind from production_calendar")
                .query((row, number) -> Map.entry(row.getDate("day").toLocalDate(), DayKind.valueOf(row.getString("kind"))))
                .list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public DayKind kind(LocalDate day) {
        DayKind kind = exceptions.get(day);
        if (kind != null) {
            return kind;
        }
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY
                ? DayKind.WEEKEND : DayKind.WORKDAY;
    }

    public boolean isWorkday(LocalDate day) {
        return kind(day) == DayKind.WORKDAY;
    }

    /**
     * Последний день срока. Счёт начинается со следующего дня после начального (ГК РФ,
     * ст. 191); если последний день нерабочий, срок оканчивается в ближайший рабочий
     * (ГК РФ, ст. 193; для претензий то же прямо в 123-ФЗ, ст. 16 ч. 2 п. 2).
     */
    public LocalDate deadline(LocalDate start, int days, DeadlineCounting counting) {
        LocalDate day = switch (counting) {
            case WORKING_DAYS -> addWorkingDays(start, days);
            case CALENDAR_DAYS -> start.plusDays(days);
            case CALENDAR_EXCEPT_HOLIDAYS -> {
                LocalDate current = start;
                for (int left = days; left > 0; ) {
                    current = current.plusDays(1);
                    if (kind(current) != DayKind.HOLIDAY) {
                        left--;
                    }
                }
                yield current;
            }
        };
        while (!isWorkday(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    /** Сколько рабочих дней после from до to включительно; 0: to уже сегодня или раньше. */
    public int workingDaysAfter(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from.plusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (isWorkday(day)) {
                count++;
            }
        }
        return count;
    }

    public LocalDate addWorkingDays(LocalDate start, int days) {
        LocalDate day = start;
        for (int left = days; left > 0; ) {
            day = day.plusDays(1);
            if (isWorkday(day)) {
                left--;
            }
        }
        return day;
    }
}
