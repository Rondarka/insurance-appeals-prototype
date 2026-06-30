package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.AppealMessage;

import java.util.List;
import java.util.UUID;

public interface AppealMessageRepository extends JpaRepository<AppealMessage, Long> {

    List<AppealMessage> findByAppealIdOrderByCreatedAtAsc(UUID appealId);
}
