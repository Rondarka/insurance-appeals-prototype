package ru.mtuci.appeals.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Снимок договора на момент регистрации обращения.
 *
 * Договор живёт в учётной системе страховой, а обращение фиксирует, каким он был, когда
 * клиент писал: последующие изменения договора не переписывают обращение задним числом.
 * contractId — ссылка на договор во внешней системе, а не внешний ключ.
 */
@Embeddable
public class ContractSnapshot {

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "contract_policy_number", nullable = false, length = 80)
    private String policyNumber;

    @Column(name = "contract_product_code", nullable = false, length = 20)
    private String productCode;

    @Column(name = "contract_product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "contract_insured_object", nullable = false, length = 500)
    private String insuredObject;

    @Column(name = "contract_valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "contract_valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "contract_terminated_on")
    private LocalDate terminatedOn;

    @Column(name = "contract_status", nullable = false, length = 20)
    private String status;

    protected ContractSnapshot() {
    }

    public ContractSnapshot(UUID contractId, String policyNumber, String productCode, String productName,
                            String insuredObject, LocalDate validFrom, LocalDate validTo,
                            LocalDate terminatedOn, String status) {
        this.contractId = contractId;
        this.policyNumber = policyNumber;
        this.productCode = productCode;
        this.productName = productName;
        this.insuredObject = insuredObject;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.terminatedOn = terminatedOn;
        this.status = status;
    }

    public UUID getContractId() {
        return contractId;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getProductName() {
        return productName;
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

    public LocalDate getTerminatedOn() {
        return terminatedOn;
    }

    public String getStatus() {
        return status;
    }
}
