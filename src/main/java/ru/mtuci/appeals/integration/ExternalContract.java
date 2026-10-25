package ru.mtuci.appeals.integration;

import ru.mtuci.appeals.domain.ContractRole;
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
        List<Coverage> coverages,
        // участники договора с ролями; counterpartyId выше всегда страхователь (решение 43)
        List<Party> parties
) {

    public record Product(String code, String name) {
    }

    /** Застрахованный риск. */
    public record Coverage(String code, String name) {
    }

    /** Участник договора. role строкой: новая роль в учётной системе не ломает разбор ответа. */
    public record Party(UUID counterpartyId, String role) {
    }

    /** Клиент участвует в договоре: страхователь или один из участников. */
    public boolean belongsTo(UUID counterparty) {
        return counterpartyId.equals(counterparty)
                || parties != null && parties.stream().anyMatch(party -> party.counterpartyId().equals(counterparty));
    }

    /**
     * Роль клиента в договоре. Страхователь главнее: тот, кто застраховал себя сам, остаётся
     * страхователем. Без списка parties участник один, страхователь: так отвечала прежняя
     * версия учётной системы.
     */
    public ContractRole roleOf(UUID counterparty) {
        if (counterpartyId.equals(counterparty)) {
            return ContractRole.POLICYHOLDER;
        }
        return parties == null ? null : parties.stream()
                .filter(party -> party.counterpartyId().equals(counterparty))
                .map(party -> ContractRole.of(party.role()))
                .filter(role -> role != null)
                .findFirst()
                .orElse(null);
    }

    /** Снимок для обращения клиента: каким договор был в момент регистрации и кем клиент в нём был. */
    public ContractSnapshot toSnapshot(UUID client) {
        return new ContractSnapshot(contractId, policyNumber, product.code(), product.name(),
                insuredObject, validFrom, validTo, terminatedOn, status, roleOf(client));
    }
}
