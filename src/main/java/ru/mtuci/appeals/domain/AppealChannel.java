package ru.mtuci.appeals.domain;

/** Как обращение поступило (решение 38): единое окно — это все каналы, а не только кабинет. */
public enum AppealChannel {
    CABINET("личный кабинет"),
    PHONE("по телефону"),
    OFFICE("в офисе");

    private final String label;

    AppealChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
