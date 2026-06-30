package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_audit")
public class EventAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "appeal_id")
    private UUID appealId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 160)
    private String routingKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected EventAudit() {
    }

    public EventAudit(UUID eventId, UUID appealId, String eventType, String routingKey,
                      String payload, Instant receivedAt) {
        this.eventId = eventId;
        this.appealId = appealId;
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getAppealId() {
        return appealId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
