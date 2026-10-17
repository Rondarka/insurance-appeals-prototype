package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.EscalationLevel;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppealRepository extends JpaRepository<Appeal, UUID>, JpaSpecificationExecutor<Appeal> {

    List<Appeal> findByCounterpartyIdOrderByCreatedAtDesc(UUID counterpartyId);

    /** Нерешённые обращения, у которых срок по закону не позже горизонта и эскалация ещё не высшая. */
    @Query("""
            select a.id from Appeal a
            where a.legalDeadline <= :horizon
              and a.status not in :finished
              and a.escalationLevel <> :top
            order by a.legalDeadline""")
    List<UUID> findEscalationCandidates(@Param("horizon") LocalDate horizon,
                                        @Param("finished") Collection<AppealStatus> finished,
                                        @Param("top") EscalationLevel top);
}
