package ru.mtuci.appeals.messaging;

import java.util.Map;
import java.util.UUID;

public record AppealDomainEvent(
        String eventType,
        UUID appealId,
        String routingKey,
        Map<String, String> details
) {
}
