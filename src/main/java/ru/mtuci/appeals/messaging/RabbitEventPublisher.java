package ru.mtuci.appeals.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

@Component
public class RabbitEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AppealDomainEvent domainEvent) {
        AppealEvent event = new AppealEvent(
                UUID.randomUUID(),
                domainEvent.eventType(),
                domainEvent.appealId(),
                domainEvent.routingKey(),
                domainEvent.details(),
                Instant.now()
        );

        rabbitTemplate.convertAndSend(RabbitTopology.APPEAL_EXCHANGE, event.routingKey(), event);
        log.info("Published {} for appeal {} with routing key {}",
                event.eventType(), event.appealId(), event.routingKey());
    }
}
