package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.AppealAttachment;

import java.util.List;
import java.util.UUID;

public interface AppealAttachmentRepository extends JpaRepository<AppealAttachment, UUID> {

    List<AppealAttachment> findByAppeal_IdOrderByUploadedAtAsc(UUID appealId);

    long countByAppeal_Id(UUID appealId);
}
