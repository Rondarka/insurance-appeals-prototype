package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_request")
public class TransferRequest {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appeal_id", nullable = false)
    private Appeal appeal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_department_id", nullable = false)
    private Department sourceDepartment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_department_id", nullable = false)
    private Department targetDepartment;

    /** Кто запросил передачу: идентификатор сотрудника в IdP и имя на момент запроса. */
    @Column(name = "requested_by_subject", nullable = false, length = 100)
    private String requestedBySubject;

    @Column(name = "requested_by_name", nullable = false, length = 160)
    private String requestedByName;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransferRequestStatus status;

    @Column(name = "reviewed_by_subject", length = 100)
    private String reviewedBySubject;

    @Column(name = "reviewed_by_name", length = 160)
    private String reviewedByName;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected TransferRequest() {
    }

    public TransferRequest(UUID id, Appeal appeal, Department sourceDepartment,
                           Department targetDepartment, String requestedBySubject, String requestedByName,
                           String reason, Instant createdAt) {
        this.id = id;
        this.appeal = appeal;
        this.sourceDepartment = sourceDepartment;
        this.targetDepartment = targetDepartment;
        this.requestedBySubject = requestedBySubject;
        this.requestedByName = requestedByName;
        this.reason = reason;
        this.status = TransferRequestStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void approve(String reviewerSubject, String reviewerName, String comment, Instant now) {
        this.status = TransferRequestStatus.APPROVED;
        this.reviewedBySubject = reviewerSubject;
        this.reviewedByName = reviewerName;
        this.reviewComment = comment;
        this.reviewedAt = now;
    }

    public void reject(String reviewerSubject, String reviewerName, String comment, Instant now) {
        this.status = TransferRequestStatus.REJECTED;
        this.reviewedBySubject = reviewerSubject;
        this.reviewedByName = reviewerName;
        this.reviewComment = comment;
        this.reviewedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Appeal getAppeal() {
        return appeal;
    }

    public Department getSourceDepartment() {
        return sourceDepartment;
    }

    public Department getTargetDepartment() {
        return targetDepartment;
    }

    public String getRequestedBySubject() {
        return requestedBySubject;
    }

    public String getRequestedByName() {
        return requestedByName;
    }

    public String getReason() {
        return reason;
    }

    public TransferRequestStatus getStatus() {
        return status;
    }

    public String getReviewedBySubject() {
        return reviewedBySubject;
    }

    public String getReviewedByName() {
        return reviewedByName;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
