package ru.mtuci.appeals.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Адрес учётной системы договоров и сколько ждать её ответа.
 * Таймаут относится к интерактивному пути: клиент в кабинете не должен ждать дольше.
 */
@ConfigurationProperties(prefix = "appeals.contracts")
public record ContractsProperties(String baseUrl, Duration timeout) {
}
