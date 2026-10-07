package ru.mtuci.appeals.service;

import java.util.UUID;

/**
 * Обращения нет — или оно чужое. Ответ одинаковый: подсистема не раскрывает, что
 * обращение с таким идентификатором существует.
 */
public class AppealNotFoundException extends RuntimeException {

    public AppealNotFoundException(UUID id) {
        super("Обращение не найдено: " + id);
    }
}
