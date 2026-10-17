package ru.mtuci.appeals.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.JoinType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.ExtendDeadlineRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.MessageResponse;
import ru.mtuci.appeals.api.ApiModels.NormResponse;
import ru.mtuci.appeals.api.ApiModels.ProductResponse;
import ru.mtuci.appeals.api.ApiModels.ContractTermsResponse;
import ru.mtuci.appeals.api.ApiModels.CoverageResponse;
import ru.mtuci.appeals.api.ApiModels.RelatedAppealResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealMessage;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.EventAudit;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.integration.ContractSourceUnavailableException;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.AppealAttachmentRepository;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.repository.EventAuditRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AppealService {

    private static final DateTimeFormatter NUMBER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_MESSAGE_LENGTH = 5000;

    private final AppealRepository appealRepository;
    private final AppealAttachmentRepository attachmentRepository;
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
    private final AppealAccess access;
    private final DeadlineNormService normService;
    private final ProductionCalendar calendar;
    private final AppealChecks checks;
    private final ZoneId zone;

    public AppealService(AppealRepository appealRepository,
                         AppealAttachmentRepository attachmentRepository,
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
                         ObjectMapper objectMapper,
                         AppealAccess access,
                         DeadlineNormService normService,
                         ProductionCalendar calendar,
                         AppealChecks checks,
                         @Value("${appeals.timezone}") String timezone) {
        this.appealRepository = appealRepository;
        this.attachmentRepository = attachmentRepository;
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
        this.access = access;
        this.normService = normService;
        this.calendar = calendar;
        this.checks = checks;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * Регистрация обращения. Договор запрашивается у учётной системы до начала транзакции:
     * сетевой вызов не должен держать соединение с базой. Транзакция охватывает только запись.
     */
    public AppealDetailResponse create(CurrentUser user, CreateAppealRequest request) {
        UUID counterpartyId = user.requireCounterparty();
        ContractSnapshot found = null;
        if (request.contractId() != null) {
            try {
                // «не найден» и «чужой» неразличимы для клиента: не раскрываем, существует ли договор
                found = contractsClient.contract(request.contractId())
                        .filter(contract -> contract.belongsTo(counterpartyId))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Договор не найден или не принадлежит выбранному клиенту"))
                        .toSnapshot();
            } catch (ContractSourceUnavailableException exception) {
                // клиенту не отказываем: договор привяжем, когда учётная система вернётся (решение 29)
                if (blank(request.productCode())) {
                    throw exception;
                }
            }
        } else if (blank(request.productCode())) {
            throw new IllegalArgumentException("Выберите договор или вид страхования");
        }
        ContractSnapshot contract = found;
        String productCode = contract != null ? contract.getProductCode() : request.productCode().trim();
        Map<String, String> details = formPolicy.details(
                request.category(), request.subcategory(), productCode, contract, request.details());

        return transactionTemplate.execute(transaction -> register(user, request, contract, productCode, details));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private AppealDetailResponse register(CurrentUser user, CreateAppealRequest request, ContractSnapshot contract,
                                          String productCode, Map<String, String> details) {
        // имя и почта — из токена на момент регистрации, как и снимок договора
        String customerName = Objects.requireNonNullElse(user.name(), "Клиент");
        String customerEmail = Objects.requireNonNullElse(user.email(), "");
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        String publicNumber = "APP-" + NUMBER_DATE.format(now) + "-"
                + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);

        Appeal appeal = new Appeal(
                id,
                publicNumber,
                user.counterpartyId(),
                customerName,
                customerEmail,
                contract,
                request.category(),
                request.subcategory(),
                writeDetails(details),
                request.subject().trim(),
                request.description().trim(),
                now
        );
        if (contract == null) {
            appeal.awaitContract(request.contractId(), productCode,
                    blank(request.policyNumber()) ? null : request.policyNumber().trim(), now);
        }
        // срок по закону считается со дня регистрации по норме, действующей на этот день
        LocalDate registeredOn = LocalDate.ofInstant(now, zone);
        DeadlineNorm norm = normService.choose(request.category(), request.subcategory(),
                productCode, details, registeredOn);
        LocalDate legalDeadline = calendar.deadline(registeredOn, norm.getTermDays(), norm.getCounting());
        appeal.applyNorm(norm, legalDeadline);
        appealRepository.save(appeal);
        historyRepository.save(new AppealHistory(
                appeal,
                "CREATED",
                "Клиент зарегистрировал обращение. Ожидается автоматическая маршрутизация.",
                customerName,
                now
        ));
        if (contract == null) {
            historyRepository.save(new AppealHistory(
                    appeal,
                    "CONTRACT_PENDING",
                    "Учётная система договоров недоступна — договор «" + formPolicy.productLabel(productCode)
                            + "» будет подобран автоматически, когда она заработает",
                    "Подсистема",
                    now
            ));
        }
        historyRepository.save(new AppealHistory(
                appeal,
                "DEADLINE_SET",
                "Срок ответа по закону — до " + legalDeadline.format(DAY) + ": " + norm.getTitle().toLowerCase(Locale.ROOT)
                        + " (" + norm.getLegalBasis() + ")",
                "Подсистема",
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
    /** Клиенту — его обращения, сотруднику — очередь его подразделения, администратору — все. */
    public List<AppealSummaryResponse> list(CurrentUser user, AppealStatus status, String departmentCode) {
        Specification<Appeal> spec;
        if (user.isAdmin()) {
            spec = departmentCode == null || departmentCode.isBlank()
                    ? Specification.where(null)
                    : (root, query, cb) -> cb.equal(root.join("department", JoinType.LEFT).get("code"), departmentCode);
        } else if (user.isClient()) {
            UUID counterpartyId = user.requireCounterparty();
            spec = (root, query, cb) -> cb.equal(root.get("counterpartyId"), counterpartyId);
        } else {
            String ownDepartment = user.requireDepartment();
            spec = (root, query, cb) ->
                    cb.equal(root.join("department", JoinType.LEFT).get("code"), ownDepartment);
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        return appealRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AppealDetailResponse get(CurrentUser user, UUID id) {
        Appeal appeal = findAppeal(id);
        access.checkView(user, appeal);
        return toDetail(appeal);
    }

    @Transactional
    public AppealDetailResponse changeStatus(CurrentUser user, UUID id, ChangeStatusRequest request) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        String employeeName = Objects.requireNonNullElse(user.name(), user.subject());

        Instant now = Instant.now();
        AppealStatus previous = appeal.getStatus();
        appeal.changeStatus(request.status(), employeeName, now);

        historyRepository.save(new AppealHistory(
                appeal,
                "STATUS_CHANGED",
                "Статус изменён: «" + previous.label() + "» → «" + request.status().label() + "»",
                employeeName,
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

    /**
     * Решить обращение — значит ответить клиенту по существу: результат и обоснование уходят
     * в переписку, у обращения появляется итог. Без ответа решить нельзя (4015-1, ст. 6.2).
     */
    @Transactional
    public AppealDetailResponse resolve(CurrentUser user, UUID id, ResolveAppealRequest request) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        String employeeName = Objects.requireNonNullElse(user.name(), user.subject());
        Instant now = Instant.now();
        AppealStatus previous = appeal.getStatus();
        appeal.resolve(request.outcome(), now);

        messageRepository.save(new AppealMessage(appeal, AuthorType.EMPLOYEE, employeeName, request.answer().trim(), now));
        historyRepository.save(new AppealHistory(
                appeal,
                "RESOLVED",
                "Клиенту направлен ответ по существу. Итог: " + request.outcome().label().toLowerCase(Locale.ROOT),
                employeeName,
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_MESSAGE_ADDED",
                appeal.getId(),
                "appeal.message.employee",
                Map.of("authorType", AuthorType.EMPLOYEE.name())
        ));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_STATUS_CHANGED",
                appeal.getId(),
                "appeal.status.resolved",
                Map.of(
                        "fromStatus", previous.name(),
                        "toStatus", AppealStatus.RESOLVED.name(),
                        "outcome", request.outcome().name()
                )
        ));
        return toDetail(appeal);
    }

    /**
     * Продление срока по закону — решение руководителя отдела (Закон № 4015-1, ст. 6.2 п. 5).
     * Новый срок отсчитывается рабочими днями от прежнего, клиент получает уведомление
     * с обоснованием в переписке.
     */
    @Transactional
    public AppealDetailResponse extendDeadline(CurrentUser user, UUID id, ExtendDeadlineRequest request) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        if (!user.isSupervisor()) {
            throw new AccessDeniedException("Продлить срок может руководитель отдела");
        }
        String supervisorName = Objects.requireNonNullElse(user.name(), user.subject());
        Instant now = Instant.now();
        String reason = request.reason().trim();
        LocalDate newDeadline = appeal.getLegalDeadline() == null
                ? null
                : calendar.addWorkingDays(appeal.getLegalDeadline(), request.days());
        appeal.extendLegalDeadline(request.days(), newDeadline, reason, LocalDate.now(zone), now);

        messageRepository.save(new AppealMessage(appeal, AuthorType.EMPLOYEE, supervisorName,
                "Срок рассмотрения вашего обращения продлён на " + request.days() + " раб. дн. — до "
                        + DAY.format(newDeadline) + ".\nПричина: " + reason,
                now));
        historyRepository.save(new AppealHistory(
                appeal,
                "DEADLINE_EXTENDED",
                "Срок по закону продлён на " + request.days() + " раб. дн., до " + DAY.format(newDeadline)
                        + ". Причина: " + reason,
                supervisorName,
                now
        ));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_DEADLINE_EXTENDED",
                appeal.getId(),
                "appeal.deadline.extended",
                Map.of("days", Integer.toString(request.days()), "legalDeadline", newDeadline.toString())
        ));
        return toDetail(appeal);
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
            throw new IllegalStateException("Обращение закрыто — создайте новое");
        }
        String text = body == null ? "" : body.trim();
        List<MultipartFile> accepted = attachmentService.accepted(appeal, files);
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
            appeal.changeStatus(AppealStatus.IN_PROGRESS, null, now);
            historyRepository.save(new AppealHistory(
                    appeal,
                    "STATUS_CHANGED",
                    "Клиент ответил — обращение снова в работе",
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

        return toDetail(appeal);
    }

    /**
     * Условия договора обращения — из учётной системы в момент запроса. Доступ — как к самому
     * обращению; сетевой вызов — вне транзакции базы.
     */
    public ContractTermsResponse contractTerms(CurrentUser user, UUID id) {
        UUID contractId = transactionTemplate.execute(transaction -> {
            Appeal appeal = findAppeal(id);
            access.checkView(user, appeal);
            if (appeal.getContract() == null) {
                throw new IllegalStateException("Договор ещё не привязан к обращению");
            }
            return appeal.getContract().getContractId();
        });
        var contract = contractsClient.contract(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Договор не найден в учётной системе"));
        return new ContractTermsResponse(
                directoryService.toContract(contract.toSnapshot()),
                contract.insuredSum(),
                contract.deductible(),
                contract.premium(),
                contract.paymentStatus(),
                contract.coverages() == null ? List.of() : contract.coverages().stream()
                        .map(item -> new CoverageResponse(item.code(), item.name())).toList()
        );
    }

    /** Другие обращения того же клиента — краткие сведения; открыть можно только доступные. */
    @Transactional(readOnly = true)
    public List<RelatedAppealResponse> related(CurrentUser user, UUID id) {
        Appeal appeal = findAppeal(id);
        access.checkView(user, appeal);
        return appealRepository.findByCounterpartyIdOrderByCreatedAtDesc(appeal.getCounterpartyId()).stream()
                .filter(other -> !other.getId().equals(appeal.getId()))
                .map(other -> new RelatedAppealResponse(
                        other.getId(),
                        other.getPublicNumber(),
                        other.getSubject(),
                        other.getCategory(),
                        other.getSubcategory(),
                        other.getStatus(),
                        other.getOutcome(),
                        other.getDepartment() == null ? null : other.getDepartment().getName(),
                        other.getContract() != null && appeal.getContract() != null
                                && other.getContract().getContractId().equals(appeal.getContract().getContractId()),
                        access.canView(user, other),
                        other.getCreatedAt()))
                .toList();
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
        List<EventAudit> events = eventAuditRepository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, 50));
        // в журнале — номер обращения, а не его технический идентификатор
        Map<UUID, String> numbers = new HashMap<>();
        appealRepository.findAllById(events.stream().map(EventAudit::getAppealId).filter(Objects::nonNull).toList())
                .forEach(appeal -> numbers.put(appeal.getId(), appeal.getPublicNumber()));
        return events.stream()
                .map(event -> toEvent(event, numbers.get(event.getAppealId())))
                .toList();
    }

    private Appeal findAppeal(UUID id) {
        return appealRepository.findById(id)
                .orElseThrow(() -> new AppealNotFoundException(id));
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
                appeal.getLegalDeadline(),
                appeal.getEscalationLevel(),
                appeal.getContractLink(),
                appeal.getOutcome(),
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

        List<AttachmentResponse> attachments = attachmentRepository
                .findByAppeal_IdOrderByUploadedAtAsc(appeal.getId())
                .stream()
                .map(attachmentService::toResponse)
                .toList();
        Map<Long, List<AttachmentResponse>> byMessage = attachments.stream()
                .filter(file -> file.messageId() != null)
                .collect(Collectors.groupingBy(AttachmentResponse::messageId));

        List<MessageResponse> messages = messageRepository.findByAppealIdOrderByCreatedAtAsc(appeal.getId())
                .stream()
                .map(item -> new MessageResponse(
                        item.getId(),
                        item.getAuthorType(),
                        item.getAuthorName(),
                        item.getBody(),
                        item.getCreatedAt(),
                        byMessage.getOrDefault(item.getId(), List.of())
                ))
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
                appeal.getLegalDeadline(),
                toNorm(appeal.getNorm()),
                appeal.getDeadlineExtendedDays(),
                appeal.getDeadlineExtensionReason(),
                appeal.getEscalationLevel(),
                appeal.getContractLink(),
                appeal.getRequestedProductCode() == null ? null
                        : new ProductResponse(appeal.getRequestedProductCode(),
                                formPolicy.productLabel(appeal.getRequestedProductCode())),
                appeal.getRequestedPolicyNumber(),
                appeal.getOutcome(),
                appeal.getResolvedAt(),
                appeal.getCreatedAt(),
                appeal.getUpdatedAt(),
                history,
                messages,
                attachments,
                transferService.forAppeal(appeal.getId()),
                appeal.getStatus().next(),
                checks.run(appeal, readDetails(appeal.getDetailsJson()),
                        appealRepository.findByCounterpartyIdOrderByCreatedAtDesc(appeal.getCounterpartyId()))
        );
    }

    private NormResponse toNorm(DeadlineNorm norm) {
        if (norm == null) {
            return null;
        }
        return new NormResponse(norm.getCode(), norm.getTitle(), norm.getTermDays(), norm.getCounting(),
                norm.getMaxExtensionDays(), norm.getLegalBasis());
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

    private EventAuditResponse toEvent(EventAudit event, String appealNumber) {
        return new EventAuditResponse(
                event.getId(),
                event.getEventId(),
                event.getAppealId(),
                appealNumber,
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
