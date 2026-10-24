package ru.mtuci.appeals.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.assistant.SignalDetector;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealMessage;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Переписка по обращению: сообщения клиента и сотрудника, при необходимости с файлами.
 * Ответ клиента на запрос возвращает обращение в работу; в сообщениях клиента ищутся
 * сигналы (решение 35).
 */
@Service
public class CorrespondenceService {

    private static final int MAX_MESSAGE_LENGTH = 5000;

    private final AppealRepository appealRepository;
    private final AppealHistoryRepository historyRepository;
    private final AppealMessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AttachmentService attachmentService;
    private final AppealAccess access;
    private final SignalDetector signals;
    private final AppealViews views;

    public CorrespondenceService(AppealRepository appealRepository, AppealHistoryRepository historyRepository,
                                 AppealMessageRepository messageRepository, ApplicationEventPublisher eventPublisher,
                                 AttachmentService attachmentService, AppealAccess access, SignalDetector signals,
                                 AppealViews views) {
        this.appealRepository = appealRepository;
        this.historyRepository = historyRepository;
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
        this.attachmentService = attachmentService;
        this.access = access;
        this.signals = signals;
        this.views = views;
    }

    /**
     * Сообщение в переписке, при необходимости с файлами: клиент досылает запрошенный документ,
     * сотрудник прикладывает расчёт или письмо. Сообщение может состоять из одних файлов.
     */
    @Transactional
    public AppealDetailResponse addMessage(CurrentUser user, UUID id, String body, List<MultipartFile> files) {
        Appeal appeal = findAppeal(id);
        // клиент пишет в своё обращение, сотрудник — в обращение своего подразделения
        AuthorType authorType;
        if (user.isClient()) {
            access.checkView(user, appeal);
            authorType = AuthorType.CLIENT;
        } else {
            access.checkWork(user, appeal);
            authorType = AuthorType.EMPLOYEE;
        }
        if (appeal.getStatus() == AppealStatus.CLOSED) {
            throw new IllegalStateException("Обращение закрыто, создайте новое");
        }
        String text = body == null ? "" : body.trim();
        List<AttachmentService.CheckedFile> accepted = attachmentService.accepted(appeal, files);
        if (text.isEmpty() && accepted.isEmpty()) {
            throw new IllegalArgumentException("Напишите сообщение или приложите файл");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Сообщение длиннее " + MAX_MESSAGE_LENGTH + " символов");
        }
        String authorName = Objects.requireNonNullElse(user.name(), user.subject());
        Instant now = Instant.now();

        AppealMessage message = messageRepository.save(new AppealMessage(
                appeal,
                authorType,
                authorName,
                text,
                now
        ));
        attachmentService.attach(appeal, message.getId(), accepted, authorName);
        if (authorType == AuthorType.CLIENT) {
            signals.record(appeal.getId(), text, now);
        }

        historyRepository.save(new AppealHistory(
                appeal,
                "MESSAGE_ADDED",
                "Добавлено сообщение от: " + authorName,
                authorName,
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_MESSAGE_ADDED",
                appeal.getId(),
                "appeal.message." + authorType.name().toLowerCase(Locale.ROOT),
                Map.of("authorType", authorType.name(), "attachments", Integer.toString(accepted.size()))
        ));

        // клиент ответил на запрос — обращение снова в работе, без участия сотрудника
        if (authorType == AuthorType.CLIENT && appeal.getStatus() == AppealStatus.WAITING_CUSTOMER) {
            appeal.changeStatus(AppealStatus.IN_PROGRESS, null, null, now);
            historyRepository.save(new AppealHistory(
                    appeal,
                    "STATUS_CHANGED",
                    "Клиент ответил, обращение снова в работе",
                    "Подсистема",
                    now
            ));
            eventPublisher.publishEvent(new AppealDomainEvent(
                    "APPEAL_STATUS_CHANGED",
                    appeal.getId(),
                    "appeal.status.in_progress",
                    Map.of("fromStatus", AppealStatus.WAITING_CUSTOMER.name(),
                            "toStatus", AppealStatus.IN_PROGRESS.name())
            ));
        }

        return views.toDetail(appeal);
    }

    private Appeal findAppeal(UUID id) {
        return appealRepository.findById(id)
                .orElseThrow(() -> new AppealNotFoundException(id));
    }
}
