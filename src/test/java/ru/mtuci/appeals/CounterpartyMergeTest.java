package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import ru.mtuci.appeals.TestIdentityProvider.TestUser;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.messaging.CounterpartyMerged;
import ru.mtuci.appeals.messaging.RabbitTopology;
import ru.mtuci.appeals.service.CounterpartyMergeService;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CDI объединил две карточки одного человека (решение 44): обращения дубля переходят на
 * основную карточку, клиент видит их в одном кабинете.
 */
class CounterpartyMergeTest extends IntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CounterpartyMergeService merges;

    @Test
    void appealsOfDuplicateMoveToMasterCard() {
        // одна и та же клиентка: карточка из офиса и карточка из кабинета
        TestUser duplicate = TestUser.client("c0a00001-0000-0000-0000-0000000000d1", "Ольга Новикова",
                "olga@example.ru", UUID.randomUUID());
        TestUser master = TestUser.client("c0a00001-0000-0000-0000-0000000000d2", "Ольга Новикова",
                "olga@example.ru", UUID.randomUUID());
        UUID appeal = as(duplicate).postForObject("/api/appeals", new CreateAppealRequest(
                null, "CASCO", null, "TECHNICAL", "LOGIN",
                Map.of("systemSection", "Личный кабинет", "device", "iPhone, Safari", "errorText", "Нет кода"),
                "Не приходит код входа", "Код подтверждения не приходит ни по СМС, ни на почту."),
                AppealDetailResponse.class).id();

        CounterpartyMerged event = new CounterpartyMerged(duplicate.counterpartyId(), master.counterpartyId(),
                Instant.now());
        rabbitTemplate.convertAndSend(RabbitTopology.CDI_EXCHANGE, "counterparty.merged", event);

        await().atMost(Duration.ofSeconds(15)).until(() -> appealsOf(master) == 1);
        assertThat(appealsOf(duplicate)).isZero();
        AppealDetailResponse moved = as(master).getForObject("/api/appeals/{id}", AppealDetailResponse.class, appeal);
        assertThat(moved.history()).extracting(HistoryResponse::action).containsOnlyOnce("COUNTERPARTY_MERGED");

        // повтор события ничего не меняет: у дубля обращений уже нет
        assertThat(merges.merge(event)).isZero();
    }
}
