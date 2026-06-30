package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.AppealHistory;

import java.util.List;
import java.util.UUID;

public interface AppealHistoryRepository extends JpaRepository<AppealHistory, Long> {

    List<AppealHistory> findByAppealIdOrderByCreatedAtAsc(UUID appealId);
}
