package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "appeal")
public class Appeal {

    @Id
    private UUID id;

    @Column(name = "public_number", nullable = false, unique = true, length = 32)
    private String publicNumber;

    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 254)
    private String customerEmail;

    /** Клиент — контрагент учётной системы, идентификатор из токена IdP. */
    @Column(name = "counterparty_id", nullable = false)
    private UUID counterpartyId;

    @Embedded
    private ContractSnapshot contract;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 60)
    private String subcategory;

    @Column(name = "details_json", nullable = false, columnDefinition = "text")
    private String detailsJson;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AppealStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppealPriority priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @Column(name = "assigned_employee", length = 160)
    private String assignedEmployee;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Appeal() {
    }

    public Appeal(UUID id, String publicNumber, UUID counterpartyId, String customerName, String customerEmail,
                  ContractSnapshot contract, String category, String subcategory, String detailsJson,
                  String subject, String description, Instant now) {
        this.id = id;
        this.publicNumber = publicNumber;
        this.counterpartyId = counterpartyId;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.contract = contract;
        this.category = category;
        this.subcategory = subcategory;
        this.detailsJson = detailsJson;
        this.subject = subject;
        this.description = description;
        this.status = AppealStatus.PENDING_ROUTING;
        this.priority = AppealPriority.NORMAL;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void routeTo(Department department, AppealPriority priority, Instant deadlineAt, Instant now) {
        this.department = department;
        this.priority = priority;
        this.deadlineAt = deadlineAt;
        this.assignedEmployee = null;
        this.status = AppealStatus.ROUTED;
        this.updatedAt = now;
    }

    public void transferTo(Department department, Instant deadlineAt, Instant now) {
        this.department = department;
        this.deadlineAt = deadlineAt;
        this.assignedEmployee = null;
        this.status = AppealStatus.ROUTED;
        this.updatedAt = now;
    }

    public void changeStatus(AppealStatus status, String employee, Instant now) {
        this.status = status;
        if (status == AppealStatus.IN_PROGRESS && employee != null && !employee.isBlank()) {
            this.assignedEmployee = employee;
        }
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicNumber() {
        return publicNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public UUID getCounterpartyId() {
        return counterpartyId;
    }

    public ContractSnapshot getContract() {
        return contract;
    }

    public String getCategory() {
        return category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public String getDetailsJson() {
        return detailsJson;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public AppealPriority getPriority() {
        return priority;
    }

    public Department getDepartment() {
        return department;
    }

    public String getAssignedEmployee() {
        return assignedEmployee;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
