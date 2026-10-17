package ru.mtuci.appeals.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mtuci.appeals.service.ProductionCalendar;
import ru.mtuci.appeals.service.ProductionCalendar.DayKind;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Дни производственного календаря за период — для календаря сроков на рабочем месте. */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    public record CalendarDay(LocalDate date, DayKind kind) {
    }

    private final ProductionCalendar calendar;

    public CalendarController(ProductionCalendar calendar) {
        this.calendar = calendar;
    }

    @GetMapping
    List<CalendarDay> days(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) {
            throw new IllegalArgumentException("Период календаря — от одного дня до трёх месяцев");
        }
        return from.datesUntil(to.plusDays(1))
                .map(day -> new CalendarDay(day, calendar.kind(day)))
                .toList();
    }
}
