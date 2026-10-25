package ru.mtuci.appeals.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ru.mtuci.appeals.service.CounterpartyMergeService;

/**
 * Слушает события CDI об объединении карточек клиента (решение 44). Обменник принадлежит
 * CDI, подсистема только привязывает к нему свою очередь.
 */
@Component
public class CounterpartyConsumer {

    private final CounterpartyMergeService merges;

    public CounterpartyConsumer(CounterpartyMergeService merges) {
        this.merges = merges;
    }

    @RabbitListener(queues = RabbitTopology.COUNTERPARTY_QUEUE)
    public void onMerged(CounterpartyMerged event) {
        merges.merge(event);
    }
}
