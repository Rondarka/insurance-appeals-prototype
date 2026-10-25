package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.mtuci.appeals.domain.AppealStatus;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Несколько экземпляров подсистемы (решение 36): плановую доставку событий выполняет тот,
 * у кого блокировка. Пока её держит «другой экземпляр», этот событие не отправляет;
 * блокировку отпустили: событие уходит, обращение маршрутизируется.
 */
class SchedulerLockTest extends IntegrationTest {

    private static final String RELAY = "outbox-relay";

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void release() {
        // ShedLock хранит время базы в UTC
        jdbc.sql("update shedlock set lock_until = timezone('utc', now()) - interval '1 second' where name = :name").param("name", RELAY).update();
    }

    @Test
    void onlyTheInstanceHoldingTheLockDeliversEvents() {
        // доставка уже работала: строка блокировки есть
        await().atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.sql("select count(*) from shedlock where name = :name").param("name", RELAY).query(Long.class).single() == 1);
        jdbc.sql("""
                        update shedlock set lock_until = timezone('utc', now()) + interval '1 hour', locked_at = timezone('utc', now()), locked_by = 'другой экземпляр'
                        where name = :name""")
                .param("name", RELAY).update();

        UUID id = createAppeal(loginProblem()).id();
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> pending(id) == 1);
        assertThat(getAppeal(id).status()).isEqualTo(AppealStatus.PENDING_ROUTING);

        release();
        awaitRouted(id);
        assertThat(pending(id)).isZero();
    }

    private long pending(UUID appealId) {
        return jdbc.sql("""
                        select count(*) from outbox_event
                        where appeal_id = :id and event_type = 'APPEAL_CREATED' and published_at is null""")
                .param("id", appealId).query(Long.class).single();
    }
}
