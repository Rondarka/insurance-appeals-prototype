package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.messaging.AppealDomainEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Transactional outbox (решение 3): событие сохраняется в одной транзакции с изменением
 * обращения и доставляется в RabbitMQ отдельно — недоступный брокер не мешает принять
 * обращение и не теряет событие.
 */
class OutboxTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void eventIsStoredWithChangeAndDelivered() {
        UUID appeal = createAppeal(loginProblem()).id();

        // событие о регистрации записано вместе с обращением
        assertThat(outboxEvents(appeal, "APPEAL_CREATED")).isEqualTo(1);

        // доставлено: маршрутизация его получила, запись помечена отправленной
        awaitRouted(appeal);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> unpublished(appeal) == 0);
    }

    @Test
    void rolledBackChangeLeavesNoEvent() {
        UUID appeal = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new AppealDomainEvent("APPEAL_CREATED", appeal, "appeal.created.test", Map.of()));
            status.setRollbackOnly();
        });

        // изменение откатилось — события тоже нет: о несуществующем обращении никто не узнает
        assertThat(outboxEvents(appeal, "APPEAL_CREATED")).isZero();
    }

    @Test
    void appealIsAcceptedWhileBrokerIsDownAndRoutedAfterwards() throws Exception {
        brokerDown();
        UUID appeal;
        try {
            // брокер лежит — клиент этого не замечает: обращение принято, событие ждёт в outbox
            appeal = createAppeal(loginProblem()).id();
            await().atMost(Duration.ofSeconds(10)).until(() -> attempts(appeal) > 0);
            assertThat(getAppeal(appeal).status()).isEqualTo(AppealStatus.PENDING_ROUTING);
            assertThat(unpublished(appeal)).isPositive();
        } finally {
            brokerUp();
        }

        // брокер вернулся — событие доставлено, обращение маршрутизировано, ничего не потеряно
        AppealDetailResponse routed = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> getAppeal(appeal), item -> item.status() != AppealStatus.PENDING_ROUTING);
        assertThat(routed.department().code()).isEqualTo("SUPPORT");
        await().atMost(Duration.ofSeconds(10)).until(() -> unpublished(appeal) == 0);
    }

    private int outboxEvents(UUID appeal, String type) {
        return jdbc.sql("select count(*) from outbox_event where appeal_id = :id and event_type = :type")
                .param("id", appeal).param("type", type).query(Integer.class).single();
    }

    private int unpublished(UUID appeal) {
        return jdbc.sql("select count(*) from outbox_event where appeal_id = :id and published_at is null")
                .param("id", appeal).query(Integer.class).single();
    }

    private int attempts(UUID appeal) {
        return jdbc.sql("select coalesce(max(attempts), 0) from outbox_event where appeal_id = :id")
                .param("id", appeal).query(Integer.class).single();
    }
}
