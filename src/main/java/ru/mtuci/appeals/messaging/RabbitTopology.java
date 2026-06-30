package ru.mtuci.appeals.messaging;

public final class RabbitTopology {

    public static final String APPEAL_EXCHANGE = "appeal.events";
    public static final String ROUTING_QUEUE = "appeal.routing";
    public static final String AUDIT_QUEUE = "appeal.audit";
    public static final String DEAD_LETTER_EXCHANGE = "appeal.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "appeal.events.dead";

    private RabbitTopology() {
    }
}
