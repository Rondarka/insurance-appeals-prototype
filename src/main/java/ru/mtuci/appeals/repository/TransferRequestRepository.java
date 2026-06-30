package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.TransferRequest;
import ru.mtuci.appeals.domain.TransferRequestStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRequestRepository extends JpaRepository<TransferRequest, UUID> {

    boolean existsByAppealIdAndStatus(UUID appealId, TransferRequestStatus status);

    List<TransferRequest> findByAppealIdOrderByCreatedAtDesc(UUID appealId);

    List<TransferRequest> findByTargetDepartmentCodeAndStatusOrderByCreatedAtAsc(
            String departmentCode, TransferRequestStatus status);

    Optional<TransferRequest> findByIdAndStatus(UUID id, TransferRequestStatus status);
}
