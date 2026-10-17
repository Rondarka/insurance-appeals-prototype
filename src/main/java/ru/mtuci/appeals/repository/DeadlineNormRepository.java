package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mtuci.appeals.domain.DeadlineNorm;

import java.time.LocalDate;
import java.util.Optional;

public interface DeadlineNormRepository extends JpaRepository<DeadlineNorm, Long> {

    /** Версия норматива, действующая на дату регистрации обращения. */
    @Query("""
            select n from DeadlineNorm n
            where n.code = :code and n.validFrom <= :date and (n.validTo is null or n.validTo >= :date)
            """)
    Optional<DeadlineNorm> findValid(@Param("code") String code, @Param("date") LocalDate date);
}
