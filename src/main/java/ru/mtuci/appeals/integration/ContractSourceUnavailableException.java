package ru.mtuci.appeals.integration;

/**
 * Учётная система договоров не ответила или ответила ошибкой, кроме 404.
 * Это сбой источника, а не отсутствие договора: повтор может помочь.
 */
public class ContractSourceUnavailableException extends RuntimeException {

    /** Цепь разомкнута: подсистема не обращалась к учётной системе вовсе (решение 30). */
    public static final int CIRCUIT_OPEN = -1;

    /** HTTP-статус ответа; 0, если ответа не было вовсе (таймаут или нет соединения); {@link #CIRCUIT_OPEN}. */
    private final int status;

    public ContractSourceUnavailableException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
