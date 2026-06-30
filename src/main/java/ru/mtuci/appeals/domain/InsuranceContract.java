package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "insurance_contract")
public class InsuranceContract {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "policy_number", nullable = false, unique = true, length = 80)
    private String policyNumber;

    @Column(name = "insurance_type", nullable = false, length = 100)
    private String insuranceType;

    @Column(name = "insured_object", nullable = false, length = 500)
    private String insuredObject;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(nullable = false, length = 30)
    private String status;

    protected InsuranceContract() {
    }

    public UUID getId() {
        return id;
    }

    public Client getClient() {
        return client;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getInsuranceType() {
        return insuranceType;
    }

    public String getInsuredObject() {
        return insuredObject;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public String getStatus() {
        return status;
    }
}
