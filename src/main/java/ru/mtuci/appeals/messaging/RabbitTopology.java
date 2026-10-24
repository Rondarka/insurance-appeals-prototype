package ru.mtuci.appeals.messaging;

public final class RabbitTopology {

    public static final String APPEAL_EXCHANGE = "appeal.events";
    public static final String ROUTING_QUEUE = "appeal.routing";
    public static final String AUDIT_QUEUE = "appeal.audit";
    /** События, по которым подсистема решает, что сообщить клиенту (решение 37). */
    public static final String NOTIFICATION_QUEUE = "appeal.notifications";
    /** Очередь сервиса коммуникаций страховой; в стенде её объявляет подсистема вместо него. */
    public static final String COMMUNICATIONS_QUEUE = "communications.appeal-notifications";
    public static final String DEAD_LETTER_EXCHANGE = "appeal.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "appeal.events.dead";

    private RabbitTopology() {
    }
}
