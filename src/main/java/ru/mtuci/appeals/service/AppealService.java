package ru.mtuci.appeals.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.JoinType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.AddMessageRequest;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.MessageResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealMessage;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.Client;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.EventAudit;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.AppealAttachmentRepository;
import ru.mtuci.appeals.repository.ClientRepository;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.repository.EventAuditRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AppealService {

    private static final DateTimeFormatter NUMBER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final AppealRepository appealRepository;
    private final AppealAttachmentRepository attachmentRepository;
    private final ClientRepository clientRepository;
    private final ContractsClient contractsClient;
    private final TransactionTemplate transactionTemplate;
    private final DepartmentRepository departmentRepository;
    private final AppealHistoryRepository historyRepository;
    private final AppealMessageRepository messageRepository;
    private final EventAuditRepository eventAuditRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AppealFormPolicy formPolicy;
    private final AttachmentService attachmentService;
    private final DirectoryService directoryService;
    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    public AppealService(AppealRepository appealRepository,
                         AppealAttachmentRepository attachmentRepository,
                         ClientRepository clientRepository,
                         ContractsClient contractsClient,
                         PlatformTransactionManager transactionManager,
                         DepartmentRepository departmentRepository,
                         AppealHistoryRepository historyRepository,
                         AppealMessageRepository messageRepository,
                         EventAuditRepository eventAuditRepository,
                         ApplicationEventPublisher eventPublisher,
                         AppealFormPolicy formPolicy,
                         AttachmentService attachmentService,
                         DirectoryService directoryService,
                         TransferService transferService,
                         ObjectMapper objectMapper) {
        this.appealRepository = appealRepository;
        this.attachmentRepository = attachmentRepository;
        this.clientRepository = clientRepository;
        this.contractsClient = contractsClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.departmentRepository = departmentRepository;
        this.historyRepository = historyRepository;
        this.messageRepository = messageRepository;
        this.eventAuditRepository = eventAuditRepository;
        this.eventPublisher = eventPublisher;
        this.formPolicy = formPolicy;
        this.attachmentService = attachmentService;
        this.directoryService = directoryService;
        this.transferService = transferService;
        this.objectMapper = objectMapper;
    }

    /**
     * Регистрация обращения. Договор запрашивается у учётной системы до начала транзакции:
     * сетевой вызов не должен держать соединение с базой. Транзакция охватывает только запись.
     */
    public AppealDetailResponse create(CreateAppealRequest request) {
        // «не найден» и «чужой» неразличимы для клиента: не раскрываем, существует ли договор
        ContractSnapshot contract = contractsClient.contract(request.contractId())
                .filter(found -> found.belongsTo(request.clientId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Договор не найден или не принадлежит выбранному клиенту"))
                .toSnapshot();
        Map<String, String> details = formPolicy.details(
                request.category(), request.subcategory(), contract, request.details());

        return transactionTemplate.execute(transaction -> register(request, contract, details));
    }

    private AppealDetailResponse register(CreateAppealRequest request, ContractSnapshot contract,
                                          Map<String, String> details) {
        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Клиент не найден: " + request.clientId()));
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        String publicNumber = "APP-" + NUMBER_DATE.format(now) + "-"
                + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);

        Appeal appeal = new Appeal(
                id,
                publicNumber,
                client,
                contract,
                request.category(),
                request.subcategory(),
                writeDetails(details),
                request.subject().trim(),
                request.description().trim(),
                now
        );
        appealRepository.save(appeal);
        historyRepository.save(new AppealHistory(
                appeal,
                "CREATED",
                "Клиент зарегистрировал обращение. Ожидается автоматическая маршрутизация.",
                client.getFullName(),
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_CREATED",
                appeal.getId(),
                "appeal.created."
                        + request.category().toLowerCase(Locale.ROOT)
                        + "."
                        + request.subcategory().toLowerCase(Locale.ROOT),
                Map.of(
                        "category", request.category(),
                        "subcategory", request.subcategory(),
                        "publicNumber", publicNumber
                )
        ));

        return toDetail(appeal);
    }

    @Transactional(readOnly = true)
    public List<AppealSummaryResponse> list(
            String departmentCode, AppealStatus status, UUID clientId) {
        Specification<Appeal> spec = Specification.where(null);

        if (departmentCode != null && !departmentCode.isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("department", JoinType.LEFT).get("code"), departmentCode));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (clientId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("client").get("id"), clientId));
        }

        return appealRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AppealDetailResponse get(UUID id) {
        return toDetail(findAppeal(id));
    }

    @Transactional
    public AppealDetailResponse changeStatus(UUID id, ChangeStatusRequest request) {
        Appeal appeal = findAppeal(id);
        validateStatusChange(appeal.getStatus(), request.status());

        Instant now = Instant.now();
        AppealStatus previous = appeal.getStatus();
        appeal.changeStatus(request.status(), request.employeeName().trim(), now);

        historyRepository.save(new AppealHistory(
                appeal,
                "STATUS_CHANGED",
                "Статус изменён: " + previous + " -> " + request.status(),
                request.employeeName().trim(),
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_STATUS_CHANGED",
                appeal.getId(),
                "appeal.status." + request.status().name().toLowerCase(Locale.ROOT),
                Map.of(
                        "fromStatus", previous.name(),
                        "toStatus", request.status().name()
                )
        ));

        return toDetail(appeal);
    }

    @Transactional
    public AppealDetailResponse addMessage(UUID id, AddMessageRequest request) {
        Appeal appeal = findAppeal(id);
        Instant now = Instant.now();

        messageRepository.save(new AppealMessage(
                appeal,
                request.authorType(),
                request.authorName().trim(),
                request.body().trim(),
                now
        ));

        historyRepository.save(new AppealHistory(
                appeal,
                "MESSAGE_ADDED",
                "Добавлено сообщение от: " + request.authorName().trim(),
                request.authorName().trim(),
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_MESSAGE_ADDED",
                appeal.getId(),
                "appeal.message." + request.authorType().name().toLowerCase(Locale.ROOT),
                Map.of("authorType", request.authorType().name())
        ));

        return toDetail(appeal);
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> departments() {
        return departmentRepository.findAll(Sort.by("name"))
                .stream()
                .map(this::toDepartment)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EventAuditResponse> recentEvents() {
        return eventAuditRepository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, 50))
                .stream()
                .map(this::toEvent)
                .toList();
    }

    private Appeal findAppeal(UUID id) {
        return appealRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Обращение не найдено: " + id));
    }

    private void validateStatusChange(AppealStatus current, AppealStatus target) {
        if (current == AppealStatus.PENDING_ROUTING) {
            throw new IllegalStateException("Нельзя менять статус до завершения маршрутизации");
        }
        if (current == AppealStatus.CLOSED) {
            throw new IllegalStateException("Закрытое обращение нельзя изменить");
        }
        if (target == AppealStatus.PENDING_ROUTING) {
            throw new IllegalArgumentException("Возврат к техническому статусу маршрутизации запрещён");
        }
    }

    private AppealSummaryResponse toSummary(Appeal appeal) {
        return new AppealSummaryResponse(
                appeal.getId(),
                appeal.getPublicNumber(),
                appeal.getCustomerName(),
                appeal.getCategory(),
                appeal.getSubcategory(),
                appeal.getSubject(),
                appeal.getStatus(),
                appeal.getPriority(),
                toDepartment(appeal.getDepartment()),
                appeal.getAssignedEmployee(),
                appeal.getDeadlineAt(),
                appeal.getCreatedAt(),
                appeal.getUpdatedAt()
        );
    }

    private AppealDetailResponse toDetail(Appeal appeal) {
        List<HistoryResponse> history = historyRepository.findByAppealIdOrderByCreatedAtAsc(appeal.getId())
                .stream()
                .map(item -> new HistoryResponse(
                        item.getId(),
                        item.getAction(),
                        item.getDescription(),
                        item.getActor(),
                        item.getCreatedAt()
                ))
                .toList();

        List<MessageResponse> messages = messageRepository.findByAppealIdOrderByCreatedAtAsc(appeal.getId())
                .stream()
                .map(item -> new MessageResponse(
                        item.getId(),
                        item.getAuthorType(),
                        item.getAuthorName(),
                        item.getBody(),
                        item.getCreatedAt()
                ))
                .toList();

        List<AttachmentResponse> attachments = attachmentRepository
                .findByAppeal_IdOrderByUploadedAtAsc(appeal.getId())
                .stream()
                .map(attachmentService::toResponse)
                .toList();

        return new AppealDetailResponse(
                appeal.getId(),
                appeal.getPublicNumber(),
                appeal.getCustomerName(),
                appeal.getCustomerEmail(),
                directoryService.toContract(appeal.getContract()),
                appeal.getCategory(),
                appeal.getSubcategory(),
                readDetails(appeal.getDetailsJson()),
                appeal.getSubject(),
                appeal.getDescription(),
                appeal.getStatus(),
                appeal.getPriority(),
                toDepartment(appeal.getDepartment()),
                appeal.getAssignedEmployee(),
                appeal.getDeadlineAt(),
                appeal.getCreatedAt(),
                appeal.getUpdatedAt(),
                history,
                messages,
                attachments,
                transferService.forAppeal(appeal.getId())
        );
    }

    private DepartmentResponse toDepartment(Department department) {
        if (department == null) {
            return null;
        }
        return new DepartmentResponse(
                department.getId(),
                department.getCode(),
                department.getName(),
                department.getDescription()
        );
    }

    private EventAuditResponse toEvent(EventAudit event) {
        return new EventAuditResponse(
                event.getId(),
                event.getEventId(),
                event.getAppealId(),
                event.getEventType(),
                event.getRoutingKey(),
                event.getReceivedAt()
        );
    }

    private String writeDetails(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Не удалось обработать дополнительные поля", exception);
        }
    }

    private Map<String, String> readDetails(String detailsJson) {
        try {
            return objectMapper.readValue(detailsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать дополнительные поля", exception);
        }
    }
}
