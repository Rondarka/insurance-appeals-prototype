package ru.mtuci.appeals.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.EventAudit;

import java.util.List;
import java.util.UUID;

public interface EventAuditRepository extends JpaRepository<EventAudit, Long> {

    boolean existsByEventId(UUID eventId);

    List<EventAudit> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
