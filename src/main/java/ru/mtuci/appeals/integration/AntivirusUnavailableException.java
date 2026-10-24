package ru.mtuci.appeals.integration;

/** Антивирус не ответил: непроверенный файл не принимается, клиенту ответ 503. */
public class AntivirusUnavailableException extends RuntimeException {

    public AntivirusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
