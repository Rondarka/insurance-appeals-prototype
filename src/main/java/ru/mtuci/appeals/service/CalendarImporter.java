package ru.mtuci.appeals.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * Загружает утверждённый производственный календарь из файлов вида
 * production-calendar-2027.csv (формат: в docs/integration-contract.md). Раз в год
 * в каталог кладётся новый файл, при старте подсистема подхватывает его.
 */
@Component
public class CalendarImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalendarImporter.class);

    private final JdbcClient jdbc;
    private final ProductionCalendar calendar;
    private final Path directory;

    public CalendarImporter(JdbcClient jdbc, ProductionCalendar calendar,
                            @Value("${appeals.calendar.path}") String directory) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.directory = Path.of(directory);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.isDirectory(directory)) {
            log.warn("Каталог производственного календаря не найден: {}. Сроки считаются по обычной неделе", directory);
        } else {
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(file -> file.getFileName().toString().matches("production-calendar-\\d{4}\\.csv"))
                        .sorted()
                        .forEach(this::importFile);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        calendar.reload();
    }

    private void importFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int count = 0;
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",");
                jdbc.sql("""
                                insert into production_calendar (day, kind) values (:day, :kind)
                                on conflict (day) do update set kind = excluded.kind
                                """)
                        .param("day", LocalDate.parse(parts[0].trim()))
                        .param("kind", ProductionCalendar.DayKind.valueOf(parts[1].trim()).name())
                        .update();
                count++;
            }
            log.info("Производственный календарь {}: загружено {} дней", file.getFileName(), count);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
