package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "appeal_history")
public class AppealHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appeal_id", nullable = false)
    private Appeal appeal;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 160)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppealHistory() {
    }

    public AppealHistory(Appeal appeal, String action, String description, String actor, Instant createdAt) {
        this.appeal = appeal;
        this.action = action;
        this.description = description;
        this.actor = actor;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getDescription() {
        return description;
    }

    public String getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
