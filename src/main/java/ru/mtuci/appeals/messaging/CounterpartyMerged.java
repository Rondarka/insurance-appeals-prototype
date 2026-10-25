package ru.mtuci.appeals.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие CDI: две карточки одного человека объединены (решение 44). duplicateId больше не
 * действует, его данные теперь у masterId. Персональных данных в событии нет, только
 * идентификаторы.
 */
public record CounterpartyMerged(UUID duplicateId, UUID masterId, Instant mergedAt) {
}
