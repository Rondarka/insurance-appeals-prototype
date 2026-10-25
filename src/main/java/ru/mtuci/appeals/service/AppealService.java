package ru.mtuci.appeals.service;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.ContractTermsResponse;
import ru.mtuci.appeals.api.ApiModels.CoverageResponse;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.RelatedAppealResponse;
import ru.mtuci.appeals.api.ApiModels.SignalBadge;
import ru.mtuci.appeals.assistant.SignalDetector;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.domain.EventAudit;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.repository.EventAuditRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Чтение обращений: очередь и карточка с учётом прав, обращения клиента, условия договора,
 * история клиента, справочник отделов и журнал событий. Изменения обращения живут в
 * отдельных сценариях: регистрация, работа в отделе, переписка.
 */
@Service
public class AppealService {

    private final AppealRepository appealRepository;
    private final AppealMessageRepository messageRepository;
    private final DepartmentRepository departmentRepository;
    private final EventAuditRepository eventAuditRepository;
    private final ContractsClient contractsClient;
    private final TransactionTemplate transactionTemplate;
    private final DirectoryService directoryService;
    private final AppealAccess access;
    private final SignalDetector signals;
    private final AppealViews views;

    public AppealService(AppealRepository appealRepository, AppealMessageRepository messageRepository,
                         DepartmentRepository departmentRepository, EventAuditRepository eventAuditRepository,
                         ContractsClient contractsClient, PlatformTransactionManager transactionManager,
                         DirectoryService directoryService, AppealAccess access, SignalDetector signals,
                         AppealViews views) {
        this.appealRepository = appealRepository;
        this.messageRepository = messageRepository;
        this.departmentRepository = departmentRepository;
        this.eventAuditRepository = eventAuditRepository;
        this.contractsClient = contractsClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.directoryService = directoryService;
        this.access = access;
        this.signals = signals;
        this.views = views;
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
            boolean supervisor = user.isSupervisor();
            spec = (root, query, cb) -> {
                Predicate own = cb.equal(root.join("department", JoinType.LEFT).get("code"), ownDepartment);
                // руководителю — ещё и обращения, которые ждут ручной классификации (решение 31)
                return supervisor ? cb.or(own, cb.equal(root.get("status"), AppealStatus.NEEDS_CLASSIFICATION)) : own;
            };
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        List<Appeal> appeals = appealRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        // одним запросом: в каких обращениях последним написал клиент — ход за сотрудником
        Set<UUID> clientReplied = appeals.isEmpty() ? Set.of()
                : messageRepository.findWithLastMessageBy(appeals.stream().map(Appeal::getId).toList(), AuthorType.CLIENT);
        // клиенту сигналы не показываются: это внутренняя оценка его обращения
        Map<UUID, List<SignalBadge>> badges = user.isClient() || appeals.isEmpty() ? Map.of()
                : signals.badges(appeals.stream().map(Appeal::getId).toList());
        return appeals.stream()
                .map(appeal -> views.toSummary(appeal, clientReplied.contains(appeal.getId()),
                        badges.getOrDefault(appeal.getId(), List.of())))
                .toList();
    }

    /** Обращения клиента для оператора контакт-центра: на звонок «что с моим обращением» (решение 38). */
    @Transactional(readOnly = true)
    public List<AppealSummaryResponse> ofClient(UUID counterpartyId) {
        return appealRepository.findAll((root, query, cb) -> cb.equal(root.get("counterpartyId"), counterpartyId),
                        Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(appeal -> views.toSummary(appeal, false, List.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AppealDetailResponse get(CurrentUser user, UUID id) {
        Appeal appeal = findAppeal(id);
        access.checkView(user, appeal);
        return views.toDetail(appeal);
    }

    /**
     * Условия договора обращения — из учётной системы в момент запроса. Доступ — как к самому
     * обращению; сетевой вызов — вне транзакции базы.
     */
    public ContractTermsResponse contractTerms(CurrentUser user, UUID id) {
        Appeal linked = transactionTemplate.execute(transaction -> {
            Appeal appeal = findAppeal(id);
            access.checkView(user, appeal);
            if (appeal.getContract() == null) {
                throw new IllegalStateException("Договор ещё не привязан к обращению");
            }
            return appeal;
        });
        var contract = contractsClient.contract(linked.getContract().getContractId())
                .orElseThrow(() -> new IllegalArgumentException("Договор не найден в учётной системе"));
        return new ContractTermsResponse(
                directoryService.toContract(contract.toSnapshot(linked.getCounterpartyId())),
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
                .map(views::toDepartment)
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
}
