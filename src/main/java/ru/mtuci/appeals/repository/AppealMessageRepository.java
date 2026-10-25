package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mtuci.appeals.domain.AppealMessage;
import ru.mtuci.appeals.domain.AuthorType;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AppealMessageRepository extends JpaRepository<AppealMessage, Long> {

    List<AppealMessage> findByAppealIdOrderByCreatedAtAsc(UUID appealId);

    /** Из этих обращений: те, где последнее сообщение написал автор указанного типа. */
    @Query("""
            select m.appeal.id from AppealMessage m
            where m.appeal.id in :appeals
              and m.authorType = :author
              and m.createdAt = (select max(o.createdAt) from AppealMessage o where o.appeal = m.appeal)""")
    Set<UUID> findWithLastMessageBy(@Param("appeals") Collection<UUID> appeals, @Param("author") AuthorType author);
}
