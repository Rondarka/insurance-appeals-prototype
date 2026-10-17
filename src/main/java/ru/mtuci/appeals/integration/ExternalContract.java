package ru.mtuci.appeals.integration;

import ru.mtuci.appeals.domain.ContractSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Договор в том виде, в каком его отдаёт учётная система (docs/contracts-api.yaml). */
public record ExternalContract(
        UUID contractId,
        UUID counterpartyId,
        String policyNumber,
        Product product,
        String insuredObject,
        LocalDate validFrom,
        LocalDate validTo,
        LocalDate terminatedOn,
        String status,
        BigDecimal insuredSum,
        BigDecimal deductible,
        BigDecimal premium,
        String paymentStatus,
        List<Coverage> coverages
) {

    public record Product(String code, String name) {
    }

    /** Застрахованный риск. */
    public record Coverage(String code, String name) {
    }

    public boolean belongsTo(UUID counterparty) {
        return counterpartyId.equals(counterparty);
    }

    /** Снимок для обращения: каким договор был в момент регистрации. */
    public ContractSnapshot toSnapshot() {
        return new ContractSnapshot(contractId, policyNumber, product.code(), product.name(),
                insuredObject, validFrom, validTo, terminatedOn, status);
    }
}
