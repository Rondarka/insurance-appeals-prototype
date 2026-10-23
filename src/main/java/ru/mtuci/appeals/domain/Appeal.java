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
import java.time.LocalDate;
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

    /** Идентификатор исполнителя в IdP: «мои обращения» не зависят от совпадения имён. */
    @Column(name = "assigned_subject", length = 100)
    private String assignedSubject;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** Целевой срок отдела — из правила маршрутизации, его ставит себе компания. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    /** Применённый норматив и срок по нему — граница, установленная законом (решение 20). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "norm_id")
    private DeadlineNorm norm;

    @Column(name = "legal_deadline")
    private LocalDate legalDeadline;

    @Column(name = "deadline_extended_days", nullable = false)
    private int deadlineExtendedDays;

    @Column(name = "deadline_extension_reason", length = 1000)
    private String deadlineExtensionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "escalation_level", nullable = false, length = 20)
    private EscalationLevel escalationLevel = EscalationLevel.NONE;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** Привязка договора (решение 29): пока не LINKED, снимок договора пуст. */
    @Enumerated(EnumType.STRING)
    @Column(name = "contract_link", nullable = false, length = 20)
    private ContractLink contractLink = ContractLink.LINKED;

    @Column(name = "requested_contract_id")
    private UUID requestedContractId;

    @Column(name = "requested_product_code", length = 30)
    private String requestedProductCode;

    @Column(name = "requested_policy_number", length = 80)
    private String requestedPolicyNumber;

    @Column(name = "contract_link_attempts", nullable = false)
    private int contractLinkAttempts;

    @Column(name = "contract_link_next_at")
    private Instant contractLinkNextAt;

    /** Демонстрационное обращение из генератора стенда (решение 33). */
    @Column(nullable = false)
    private boolean demo;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AppealOutcome outcome;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

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

    /**
     * Учётная система недоступна: договор подберём позже по тому, что указал клиент —
     * договор из списка, вид страхования, номер (решение 29).
     */
    public void awaitContract(UUID requestedContractId, String productCode, String policyNumber, Instant now) {
        if (contract != null) {
            throw new IllegalStateException("Договор уже привязан");
        }
        this.contractLink = ContractLink.PENDING;
        this.requestedContractId = requestedContractId;
        this.requestedProductCode = productCode;
        this.requestedPolicyNumber = policyNumber;
        this.contractLinkNextAt = now;
    }

    public void linkContract(ContractSnapshot contract, String detailsJson, Instant now) {
        if (contractLink == ContractLink.LINKED) {
            throw new IllegalStateException("Договор уже привязан");
        }
        this.contract = contract;
        this.detailsJson = detailsJson;
        this.contractLink = ContractLink.LINKED;
        this.contractLinkNextAt = null;
        this.updatedAt = now;
    }

    /** Подходит несколько договоров или ни одного — выбирает человек. */
    public void requireContractChoice(ContractLink reason, Instant now) {
        if (reason != ContractLink.NEEDS_CHOICE && reason != ContractLink.NOT_FOUND) {
            throw new IllegalArgumentException("Не причина для выбора договора: " + reason);
        }
        this.contractLink = reason;
        this.contractLinkNextAt = null;
        this.updatedAt = now;
    }

    public void postponeContractLink(Instant nextAttempt) {
        this.contractLinkAttempts++;
        this.contractLinkNextAt = nextAttempt;
    }

    /** Вид страхования: по договору, а пока договор не привязан — указанный клиентом. */
    public String getProductCode() {
        return contract != null ? contract.getProductCode() : requestedProductCode;
    }

    public void applyNorm(DeadlineNorm norm, LocalDate legalDeadline) {
        this.norm = norm;
        this.legalDeadline = legalDeadline;
    }

    /**
     * Продление срока по закону: только если норматив его допускает, не больше чем на
     * допустимое число рабочих дней, один раз и пока срок не истёк — задним числом не продлить.
     * Новую дату считает производственный календарь.
     */
    public void extendLegalDeadline(int days, LocalDate newDeadline, String reason, LocalDate today, Instant now) {
        if (norm == null || legalDeadline == null) {
            throw new IllegalStateException("У обращения нет срока по закону — продлевать нечего");
        }
        if (status == AppealStatus.RESOLVED || status == AppealStatus.CLOSED) {
            throw new IllegalStateException("Обращение уже решено");
        }
        if (norm.getMaxExtensionDays() == 0) {
            throw new IllegalStateException("Норматив «" + norm.getTitle() + "» продления не предусматривает");
        }
        if (deadlineExtendedDays > 0) {
            throw new IllegalStateException("Срок уже продлевался на " + deadlineExtendedDays + " раб. дн.");
        }
        if (days < 1 || days > norm.getMaxExtensionDays()) {
            throw new IllegalArgumentException("Продлить можно на 1–" + norm.getMaxExtensionDays() + " рабочих дней");
        }
        if (today.isAfter(legalDeadline)) {
            throw new IllegalStateException("Срок уже истёк — продлить его задним числом нельзя");
        }
        this.legalDeadline = newDeadline;
        this.deadlineExtendedDays = days;
        this.deadlineExtensionReason = reason;
        // срок отодвинут — прежняя эскалация больше не актуальна, планировщик оценит заново
        this.escalationLevel = EscalationLevel.NONE;
        this.escalatedAt = null;
        this.updatedAt = now;
    }

    /**
     * Эскалация по сроку закона: уровень только растёт, вместе с ним растёт приоритет —
     * «срок истекает» не ниже высокого, «срок нарушен» — критический. Клиенту это не видно,
     * поэтому дата изменения обращения не трогается.
     *
     * @return false, если обращение уже на этом уровне или выше
     */
    public boolean escalate(EscalationLevel level, Instant now) {
        if (level.compareTo(escalationLevel) <= 0) {
            return false;
        }
        this.escalationLevel = level;
        this.escalatedAt = now;
        AppealPriority atLeast = level == EscalationLevel.BREACHED ? AppealPriority.CRITICAL : AppealPriority.HIGH;
        if (priority.compareTo(atLeast) < 0) {
            this.priority = atLeast;
        }
        return true;
    }

    /** Правила маршрутизации нет: обращение ждёт, пока руководитель выберет отдел. Клиенту это не видно. */
    public void requireClassification() {
        if (status != AppealStatus.PENDING_ROUTING) {
            throw new IllegalStateException("Классификация нужна только обращению, которое ещё не маршрутизировано");
        }
        this.status = AppealStatus.NEEDS_CLASSIFICATION;
    }

    public void routeTo(Department department, AppealPriority priority, Instant deadlineAt, Instant now) {
        this.department = department;
        this.priority = priority;
        this.deadlineAt = deadlineAt;
        unassign();
        this.status = AppealStatus.ROUTED;
        this.updatedAt = now;
    }

    public void transferTo(Department department, Instant deadlineAt, Instant now) {
        this.department = department;
        this.deadlineAt = deadlineAt;
        unassign();
        this.status = AppealStatus.ROUTED;
        this.updatedAt = now;
    }

    /** Сотрудник меняет статус только по разрешённым переходам — см. {@link AppealStatus#next()}. */
    public void changeStatus(AppealStatus status, String employeeSubject, String employeeName, Instant now) {
        if (status == AppealStatus.RESOLVED) {
            throw new IllegalStateException("Решить обращение можно только ответом клиенту по существу");
        }
        checkTransition(status);
        if (this.status == AppealStatus.RESOLVED && status == AppealStatus.IN_PROGRESS) {
            // клиент не согласен с решением — прежний итог больше не действует;
            // при закрытии итог остаётся: это и есть результат рассмотрения
            this.outcome = null;
            this.resolvedAt = null;
        }
        this.status = status;
        // взял в работу — стал исполнителем
        if (status == AppealStatus.IN_PROGRESS && employeeSubject != null) {
            assign(employeeSubject, employeeName, now);
        }
        this.updatedAt = now;
    }

    /** Назначить исполнителя: руководитель или сам сотрудник, взявший обращение в работу. */
    public void assign(String subject, String name, Instant now) {
        this.assignedSubject = subject;
        this.assignedEmployee = name;
        this.assignedAt = now;
    }

    private void unassign() {
        this.assignedSubject = null;
        this.assignedEmployee = null;
        this.assignedAt = null;
    }

    /** Решение — это ответ клиенту по существу с итогом; ответ сохраняет сервис. */
    public void resolve(AppealOutcome outcome, Instant now) {
        checkTransition(AppealStatus.RESOLVED);
        this.status = AppealStatus.RESOLVED;
        this.outcome = outcome;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    private void checkTransition(AppealStatus target) {
        if (!this.status.canMoveTo(target)) {
            throw new IllegalStateException("Нельзя перевести обращение из статуса «"
                    + this.status.label() + "» в «" + target.label() + "»");
        }
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

    public String getAssignedSubject() {
        return assignedSubject;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public AppealOutcome getOutcome() {
        return outcome;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public DeadlineNorm getNorm() {
        return norm;
    }

    public LocalDate getLegalDeadline() {
        return legalDeadline;
    }

    public int getDeadlineExtendedDays() {
        return deadlineExtendedDays;
    }

    public String getDeadlineExtensionReason() {
        return deadlineExtensionReason;
    }

    public void markDemo() {
        this.demo = true;
    }

    public boolean isDemo() {
        return demo;
    }

    public ContractLink getContractLink() {
        return contractLink;
    }

    public UUID getRequestedContractId() {
        return requestedContractId;
    }

    public String getRequestedProductCode() {
        return requestedProductCode;
    }

    public String getRequestedPolicyNumber() {
        return requestedPolicyNumber;
    }

    public int getContractLinkAttempts() {
        return contractLinkAttempts;
    }

    public EscalationLevel getEscalationLevel() {
        return escalationLevel;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
