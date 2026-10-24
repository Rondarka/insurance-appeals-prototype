package ru.mtuci.appeals;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Несколько экземпляров подсистемы (решение 36): запросы и потребители очередей делят
 * нагрузку, а каждую фоновую задачу в один момент выполняет один экземпляр. Блокировка —
 * строка в таблице shedlock; время берётся из базы, а не с часов экземпляров. Упавший
 * экземпляр держит блокировку не дольше lockAtMostFor — дальше задачу подхватит другой.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
