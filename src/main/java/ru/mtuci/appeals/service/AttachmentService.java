package ru.mtuci.appeals.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealAttachment;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealAttachmentRepository;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private final AppealRepository appealRepository;
    private final AppealAttachmentRepository attachmentRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Path storageRoot;
    private final int maxCount;

    public AttachmentService(AppealRepository appealRepository,
                             AppealAttachmentRepository attachmentRepository,
                             AppealHistoryRepository historyRepository,
                             ApplicationEventPublisher eventPublisher,
                             @Value("${appeals.attachments.path}") String storagePath,
                             @Value("${appeals.attachments.max-count}") int maxCount) {
        this.appealRepository = appealRepository;
        this.attachmentRepository = attachmentRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxCount = maxCount;
    }

    @PostConstruct
    void initializeStorage() throws IOException {
        Files.createDirectories(storageRoot);
    }

    @Transactional
    public List<AttachmentResponse> store(UUID appealId, List<MultipartFile> files) {
        Appeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new IllegalArgumentException("Обращение не найдено: " + appealId));

        List<MultipartFile> actualFiles = files == null
                ? List.of()
                : files.stream().filter(file -> !file.isEmpty()).toList();

        long existingCount = attachmentRepository.countByAppeal_Id(appealId);
        if (actualFiles.isEmpty()) {
            throw new IllegalArgumentException("Не выбраны файлы для загрузки");
        }
        if (existingCount + actualFiles.size() > maxCount) {
            throw new IllegalArgumentException("К обращению можно прикрепить не более " + maxCount + " файлов");
        }

        actualFiles.forEach(this::validateFile);

        Instant now = Instant.now();
        List<AppealAttachment> stored = new ArrayList<>();
        try {
            for (MultipartFile file : actualFiles) {
                UUID id = UUID.randomUUID();
                String originalName = cleanOriginalName(file.getOriginalFilename());
                String storageName = id + extension(originalName);
                Path target = resolveStoragePath(storageName);

                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }

                stored.add(attachmentRepository.save(new AppealAttachment(
                        id,
                        appeal,
                        originalName,
                        storageName,
                        file.getContentType(),
                        file.getSize(),
                        now
                )));
            }
        } catch (IOException exception) {
            stored.forEach(item -> deleteQuietly(item.getStorageName()));
            throw new IllegalStateException("Не удалось сохранить вложение", exception);
        }

        historyRepository.save(new AppealHistory(
                appeal,
                "ATTACHMENTS_ADDED",
                "Добавлены вложения: " + stored.stream()
                        .map(AppealAttachment::getOriginalName)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse(""),
                appeal.getCustomerName(),
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_ATTACHMENTS_ADDED",
                appeal.getId(),
                "appeal.attachment.added",
                Map.of("count", Integer.toString(stored.size()))
        ));

        return stored.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AttachmentDownload download(UUID attachmentId) {
        AppealAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException("Вложение не найдено: " + attachmentId));
        try {
            Resource resource = new UrlResource(resolveStoragePath(attachment.getStorageName()).toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalStateException("Файл вложения отсутствует в хранилище");
            }
            return new AttachmentDownload(attachment, resource);
        } catch (MalformedURLException exception) {
            throw new IllegalStateException("Не удалось открыть вложение", exception);
        }
    }

    public AttachmentResponse toResponse(AppealAttachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getOriginalName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getUploadedAt(),
                "/api/attachments/" + attachment.getId()
        );
    }

    private void validateFile(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Файл превышает допустимый размер 10 МБ: "
                    + cleanOriginalName(file.getOriginalFilename()));
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Разрешены только PDF, JPG и PNG: "
                    + cleanOriginalName(file.getOriginalFilename()));
        }
    }

    private String cleanOriginalName(String name) {
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        return Path.of(name).getFileName().toString().replaceAll("[\\r\\n]", "");
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private Path resolveStoragePath(String storageName) {
        Path target = storageRoot.resolve(storageName).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Недопустимое имя файла");
        }
        return target;
    }

    private void deleteQuietly(String storageName) {
        try {
            Files.deleteIfExists(resolveStoragePath(storageName));
        } catch (IOException ignored) {
        }
    }

    public record AttachmentDownload(AppealAttachment attachment, Resource resource) {
    }
}
