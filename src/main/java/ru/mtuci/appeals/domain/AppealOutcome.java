package ru.mtuci.appeals.domain;

/**
 * Итог рассмотрения. Для претензии закон требует мотивированный ответ об удовлетворении,
 * частичном удовлетворении или отказе (123-ФЗ, ст. 16 ч. 2); для прочих обращений
 * результатом бывают и разъяснения (Закон № 4015-1, ст. 6.2 п. 6).
 */
public enum AppealOutcome {
    SATISFIED("Требование удовлетворено"),
    PARTIALLY_SATISFIED("Требование удовлетворено частично"),
    REJECTED("В удовлетворении отказано"),
    EXPLAINED("Даны разъяснения");

    private final String label;

    AppealOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
