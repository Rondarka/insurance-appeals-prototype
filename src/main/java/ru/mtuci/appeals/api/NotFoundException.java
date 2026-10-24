package ru.mtuci.appeals.api;

/** Искомого нет, например договора с таким номером: ответ 404 с понятным сообщением. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
