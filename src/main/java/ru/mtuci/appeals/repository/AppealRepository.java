package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.mtuci.appeals.domain.Appeal;

import java.util.List;
import java.util.UUID;

public interface AppealRepository extends JpaRepository<Appeal, UUID>, JpaSpecificationExecutor<Appeal> {

    List<Appeal> findByCounterpartyIdOrderByCreatedAtDesc(UUID counterpartyId);
}
