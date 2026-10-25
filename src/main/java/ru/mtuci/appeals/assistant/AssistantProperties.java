package ru.mtuci.appeals.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Локальная модель помощника (решение 35): OpenAI-совместимый API, который дают Ollama,
 * LM Studio и vLLM. Адрес только внутри контура страховой: в запросе текст обращения,
 * а это персональные данные. Включается не здесь, а настройкой администратора.
 */
@ConfigurationProperties(prefix = "appeals.assistant")
public record AssistantProperties(Model model) {

    /** openDuration: сколько не обращаться к модели после серии сбоев; подсказки в это время дают правила. */
    public record Model(String baseUrl, String name, Duration timeout, String apiKey, Duration openDuration) {
    }
}
