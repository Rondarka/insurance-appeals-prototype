package ru.mtuci.appeals.domain;

import java.util.List;

/**
 * Жизненный цикл обращения. Переходы, доступные сотруднику, задаёт {@link #next()}:
 * интерфейс показывает только их, сервер другие отклоняет. Маршрутизацию
 * (PENDING_ROUTING → ROUTED) и передачу между отделами выполняет подсистема сама.
 */
public enum AppealStatus {
    PENDING_ROUTING("Ожидает маршрутизации"),
    ROUTED("Назначено отделу"),
    IN_PROGRESS("В работе"),
    WAITING_CUSTOMER("Ждём ответа клиента"),
    RESOLVED("Решено"),
    CLOSED("Закрыто");

    private final String label;

    AppealStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public List<AppealStatus> next() {
        return switch (this) {
            case PENDING_ROUTING, CLOSED -> List.of();
            case ROUTED -> List.of(IN_PROGRESS);
            case IN_PROGRESS -> List.of(WAITING_CUSTOMER, RESOLVED);
            // клиент не ответил, но вопрос решён без него — можно закрыть и так
            case WAITING_CUSTOMER -> List.of(IN_PROGRESS, RESOLVED);
            // клиент не согласен с решением — обращение возвращается в работу
            case RESOLVED -> List.of(CLOSED, IN_PROGRESS);
        };
    }

    public boolean canMoveTo(AppealStatus target) {
        return next().contains(target);
    }
}
