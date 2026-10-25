package ru.mtuci.appeals.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealAttachment;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealAttachmentRepository;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.integration.AntivirusClient;
import ru.mtuci.appeals.security.CurrentUser;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    /** Тип файла определяется по первым байтам, а не по имени и не по слову браузера (решение 39). */
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", "%PDF-".getBytes(StandardCharsets.US_ASCII),
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
    private static final Map<String, String> EXTENSIONS = Map.of(
            "application/pdf", ".pdf", "image/jpeg", ".jpg", "image/png", ".png");

    private final AppealRepository appealRepository;
    private final AppealAttachmentRepository attachmentRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Path storageRoot;
    private final int maxCount;
    private final int maxPerAppeal;
    private final AppealAccess access;
    private final AntivirusClient antivirus;

    public AttachmentService(AppealRepository appealRepository,
                             AppealAttachmentRepository attachmentRepository,
                             AppealHistoryRepository historyRepository,
                             ApplicationEventPublisher eventPublisher,
                             @Value("${appeals.attachments.path}") String storagePath,
                             @Value("${appeals.attachments.max-count}") int maxCount,
                             @Value("${appeals.attachments.max-per-appeal}") int maxPerAppeal,
                             AppealAccess access,
                             AntivirusClient antivirus) {
        this.appealRepository = appealRepository;
        this.attachmentRepository = attachmentRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxCount = maxCount;
        this.maxPerAppeal = maxPerAppeal;
        this.access = access;
        this.antivirus = antivirus;
    }

    @PostConstruct
    void initializeStorage() throws IOException {
        Files.createDirectories(storageRoot);
    }

    /**
     * Файлы из формы обращения: клиент прикладывает их сразу после отправки. Дослать документ
     * позже: сообщением в переписке ({@link #attach}), чтобы сотрудник видел, к чему он.
     */
    @Transactional
    public List<AttachmentResponse> store(CurrentUser user, UUID appealId, List<MultipartFile> files) {
        Appeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new AppealNotFoundException(appealId));
        access.checkView(user, appeal);
        if (!user.isClient()) {
            throw new AccessDeniedException("Сотрудник прикладывает файлы к сообщению в переписке");
        }
        if (appeal.getStatus() == AppealStatus.CLOSED) {
            throw new IllegalStateException("Обращение закрыто, создайте новое");
        }
        List<CheckedFile> accepted = accepted(appeal, files);
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("Не выбраны файлы для загрузки");
        }
        return attach(appeal, null, accepted, appeal.getCustomerName()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Проверяет файлы до того, как что-либо сохранено: сколько за раз и всего у обращения,
     * размер, тип по содержимому и антивирусом. Возвращает выбранные файлы без пустых полей
     * формы вместе с настоящим типом.
     */
    public List<CheckedFile> accepted(Appeal appeal, List<MultipartFile> files) {
        List<MultipartFile> actualFiles = files == null
                ? List.of()
                : files.stream().filter(file -> !file.isEmpty()).toList();
        if (actualFiles.size() > maxCount) {
            throw new IllegalArgumentException("За один раз можно приложить не более " + maxCount + " файлов");
        }
        if (attachmentRepository.countByAppeal_Id(appeal.getId()) + actualFiles.size() > maxPerAppeal) {
            throw new IllegalArgumentException("К обращению можно приложить не более " + maxPerAppeal + " файлов");
        }
        return actualFiles.stream().map(this::check).toList();
    }

    /** Файл и его тип, определённый по содержимому. */
    public record CheckedFile(MultipartFile file, String contentType) {
    }

    /**
     * Сохраняет проверенные файлы обращения или его сообщения (messageId). Если транзакция
     * откатится (например, не сохранилось само сообщение), файлы удаляются и с диска.
     */
    public List<AppealAttachment> attach(Appeal appeal, Long messageId, List<CheckedFile> files, String actor) {
        if (files.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        List<String> written = new ArrayList<>();
        deleteOnRollback(written);
        List<AppealAttachment> stored = new ArrayList<>();
        try {
            for (CheckedFile checked : files) {
                MultipartFile file = checked.file();
                UUID id = UUID.randomUUID();
                String originalName = cleanOriginalName(file.getOriginalFilename());
                // расширение в хранилище берётся по настоящему типу, а не из имени, которое прислал клиент
                String storageName = id + EXTENSIONS.get(checked.contentType());
                Path target = resolveStoragePath(storageName);

                written.add(storageName);
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }

                stored.add(attachmentRepository.save(new AppealAttachment(
                        id,
                        appeal,
                        messageId,
                        originalName,
                        storageName,
                        checked.contentType(),
                        file.getSize(),
                        now
                )));
            }
        } catch (IOException exception) {
            written.forEach(this::deleteQuietly);
            throw new IllegalStateException("Не удалось сохранить вложение", exception);
        }

        historyRepository.save(new AppealHistory(
                appeal,
                "ATTACHMENTS_ADDED",
                "Добавлены вложения: " + stored.stream()
                        .map(AppealAttachment::getOriginalName)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse(""),
                actor,
                now
        ));

        Map<String, String> details = new LinkedHashMap<>();
        details.put("count", Integer.toString(stored.size()));
        if (messageId != null) {
            details.put("messageId", messageId.toString());
        }
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_ATTACHMENTS_ADDED",
                appeal.getId(),
                "appeal.attachment.added",
                details
        ));

        return stored;
    }

    @Transactional(readOnly = true)
    public AttachmentDownload download(CurrentUser user, UUID attachmentId) {
        AppealAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException("Вложение не найдено: " + attachmentId));
        access.checkView(user, attachment.getAppeal());
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
                attachment.getMessageId(),
                attachment.getOriginalName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getUploadedAt(),
                "/api/attachments/" + attachment.getId()
        );
    }

    private CheckedFile check(MultipartFile file) {
        String name = cleanOriginalName(file.getOriginalFilename());
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Файл превышает допустимый размер 10 МБ: " + name);
        }
        String contentType = detectType(file)
                .orElseThrow(() -> new IllegalArgumentException("Разрешены только PDF, JPG и PNG: " + name));
        if (antivirus.enabled()) {
            try (InputStream content = file.getInputStream()) {
                antivirus.scan(content).ifPresent(threat -> {
                    throw new IllegalArgumentException("Файл не прошёл антивирусную проверку: " + name);
                });
            } catch (IOException exception) {
                throw new IllegalStateException("Не удалось прочитать вложение " + name, exception);
            }
        }
        return new CheckedFile(file, contentType);
    }

    /** PDF, JPG или PNG по первым байтам; переименованный файл другого вида не пройдёт. */
    private static Optional<String> detectType(MultipartFile file) {
        byte[] head;
        try (InputStream content = file.getInputStream()) {
            head = content.readNBytes(8);
        } catch (IOException exception) {
            return Optional.empty();
        }
        return SIGNATURES.entrySet().stream()
                .filter(signature -> startsWith(head, signature.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static boolean startsWith(byte[] head, byte[] signature) {
        return head.length >= signature.length && Arrays.equals(head, 0, signature.length, signature, 0, signature.length);
    }

    private String cleanOriginalName(String name) {
        if (name == null || name.isBlank()) {
            return "attachment";
        }
        return Path.of(name).getFileName().toString().replaceAll("[\\r\\n]", "");
    }

    private Path resolveStoragePath(String storageName) {
        Path target = storageRoot.resolve(storageName).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Недопустимое имя файла");
        }
        return target;
    }

    private void deleteOnRollback(List<String> written) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    written.forEach(AttachmentService.this::deleteQuietly);
                }
            }
        });
    }

    /** Удалить файлы из хранилища после обезличивания (решение 41): строки о них уже удалены. */
    public void deleteStored(List<String> storageNames) {
        storageNames.forEach(this::deleteQuietly);
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
