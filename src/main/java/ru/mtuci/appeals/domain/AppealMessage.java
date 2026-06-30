package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "appeal_message")
public class AppealMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appeal_id", nullable = false)
    private Appeal appeal;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 30)
    private AuthorType authorType;

    @Column(name = "author_name", nullable = false, length = 160)
    private String authorName;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppealMessage() {
    }

    public AppealMessage(Appeal appeal, AuthorType authorType, String authorName, String body, Instant createdAt) {
        this.appeal = appeal;
        this.authorType = authorType;
        this.authorName = authorName;
        this.body = body;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public AuthorType getAuthorType() {
        return authorType;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
