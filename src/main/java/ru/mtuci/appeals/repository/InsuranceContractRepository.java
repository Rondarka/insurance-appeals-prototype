package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.InsuranceContract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsuranceContractRepository extends JpaRepository<InsuranceContract, UUID> {

    List<InsuranceContract> findByClientIdOrderByValidToDesc(UUID clientId);

    Optional<InsuranceContract> findByIdAndClientId(UUID id, UUID clientId);
}
