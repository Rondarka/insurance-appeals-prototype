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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private Employee requestedBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransferRequestStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private Employee reviewedBy;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected TransferRequest() {
    }

    public TransferRequest(UUID id, Appeal appeal, Department sourceDepartment,
                           Department targetDepartment, Employee requestedBy,
                           String reason, Instant createdAt) {
        this.id = id;
        this.appeal = appeal;
        this.sourceDepartment = sourceDepartment;
        this.targetDepartment = targetDepartment;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.status = TransferRequestStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void approve(Employee reviewer, String comment, Instant now) {
        this.status = TransferRequestStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewComment = comment;
        this.reviewedAt = now;
    }

    public void reject(Employee reviewer, String comment, Instant now) {
        this.status = TransferRequestStatus.REJECTED;
        this.reviewedBy = reviewer;
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

    public Employee getRequestedBy() {
        return requestedBy;
    }

    public String getReason() {
        return reason;
    }

    public TransferRequestStatus getStatus() {
        return status;
    }

    public Employee getReviewedBy() {
        return reviewedBy;
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
