package ru.mtuci.appeals.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Адрес учётной системы договоров и как с ней обращаться при сбоях (решение 30).
 * Таймаут относится к интерактивному пути: клиент в кабинете не должен ждать дольше.
 */
@ConfigurationProperties(prefix = "appeals.contracts")
public record ContractsProperties(String baseUrl, Duration timeout, Circuit circuit, RetrySettings retry) {

    /** Из последних slidingWindow вызовов (не меньше minimumCalls) сбоем закончились failureRateThreshold %: цепь размыкается на openDuration. */
    public record Circuit(int slidingWindow, int minimumCalls, float failureRateThreshold, Duration openDuration) {
    }

    /** Повтор на интерактивном пути: сколько попыток всего и пауза между ними. */
    public record RetrySettings(int maxAttempts, Duration pause) {
    }
}
