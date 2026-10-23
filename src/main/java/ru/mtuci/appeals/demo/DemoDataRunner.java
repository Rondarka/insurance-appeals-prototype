package ru.mtuci.appeals.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * На стенде — наполнить подсистему демонстрационными обращениями, если их ещё нет
 * (appeals.demo.enabled). После загрузки календаря и нормативов: ApplicationReadyEvent
 * наступает, когда все ApplicationRunner отработали.
 */
@Component
@ConditionalOnProperty(name = "appeals.demo.enabled", havingValue = "true")
public class DemoDataRunner {

    private final DemoDataGenerator generator;
    private final int count;
    private final int days;
    private final long seed;

    public DemoDataRunner(DemoDataGenerator generator,
                          @Value("${appeals.demo.count}") int count,
                          @Value("${appeals.demo.days}") int days,
                          @Value("${appeals.demo.seed}") long seed) {
        this.generator = generator;
        this.count = count;
        this.days = days;
        this.seed = seed;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fill() {
        if (!generator.hasDemoData()) {
            generator.generate(count, days, seed);
        }
    }
}
