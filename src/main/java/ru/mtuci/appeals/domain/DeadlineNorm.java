package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Норматив срока хранится версиями (решение 20). Изменение закона даёт новую строку с новой
 * датой начала действия; обращение хранит ссылку на ту версию, по которой считался срок.
 */
@Entity
@Table(name = "deadline_norm")
public class DeadlineNorm {

    @Id
    private Long id;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "term_days", nullable = false)
    private int termDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeadlineCounting counting;

    @Column(name = "max_extension_days", nullable = false)
    private int maxExtensionDays;

    @Column(length = 40)
    private String condition;

    @Column(name = "legal_basis", nullable = false, length = 300)
    private String legalBasis;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    protected DeadlineNorm() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public int getTermDays() {
        return termDays;
    }

    public DeadlineCounting getCounting() {
        return counting;
    }

    public int getMaxExtensionDays() {
        return maxExtensionDays;
    }

    public String getCondition() {
        return condition;
    }

    public String getLegalBasis() {
        return legalBasis;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }
}
