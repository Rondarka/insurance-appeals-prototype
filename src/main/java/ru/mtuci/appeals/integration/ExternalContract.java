package ru.mtuci.appeals.integration;

import ru.mtuci.appeals.domain.ContractSnapshot;

import java.time.LocalDate;
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
        String status
) {

    public record Product(String code, String name) {
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
