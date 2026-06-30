package ru.mtuci.appeals.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.domain.EventAudit;
import ru.mtuci.appeals.repository.EventAuditRepository;

import java.time.Instant;

@Component
public class AuditConsumer {

    private final EventAuditRepository eventAuditRepository;
    private final ObjectMapper objectMapper;

    public AuditConsumer(EventAuditRepository eventAuditRepository, ObjectMapper objectMapper) {
        this.eventAuditRepository = eventAuditRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitTopology.AUDIT_QUEUE)
    @Transactional
    public void audit(AppealEvent event) throws JsonProcessingException {
        if (eventAuditRepository.existsByEventId(event.eventId())) {
            return;
        }

        eventAuditRepository.save(new EventAudit(
                event.eventId(),
                event.appealId(),
                event.eventType(),
                event.routingKey(),
                objectMapper.writeValueAsString(event),
                Instant.now()
        ));
    }
}
