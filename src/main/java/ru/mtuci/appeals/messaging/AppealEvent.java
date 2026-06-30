package ru.mtuci.appeals.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AppealEvent(
        UUID eventId,
        String eventType,
        UUID appealId,
        String routingKey,
        Map<String, String> details,
        Instant occurredAt
) {
}
