package ru.mtuci.appeals.assistant;

/** Модель не ответила или ответила не по формату — подсказку дают правила. */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
