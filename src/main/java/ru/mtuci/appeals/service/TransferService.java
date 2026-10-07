package ru.mtuci.appeals.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.CreateTransferRequest;
import ru.mtuci.appeals.api.ApiModels.ReviewTransferRequest;
import ru.mtuci.appeals.api.ApiModels.TransferRequestResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.Employee;
import ru.mtuci.appeals.domain.TransferRequest;
import ru.mtuci.appeals.domain.TransferRequestStatus;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.repository.EmployeeRepository;
import ru.mtuci.appeals.repository.RoutingRuleRepository;
import ru.mtuci.appeals.repository.TransferRequestRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TransferService {

    private final TransferRequestRepository transferRepository;
    private final AppealRepository appealRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final DirectoryService directoryService;
    private final AppealAccess access;

    public TransferService(TransferRequestRepository transferRepository,
                           AppealRepository appealRepository,
                           DepartmentRepository departmentRepository,
                           EmployeeRepository employeeRepository,
                           RoutingRuleRepository routingRuleRepository,
                           AppealHistoryRepository historyRepository,
                           ApplicationEventPublisher eventPublisher,
                           DirectoryService directoryService,
                           AppealAccess access) {
        this.transferRepository = transferRepository;
        this.appealRepository = appealRepository;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.routingRuleRepository = routingRuleRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.directoryService = directoryService;
        this.access = access;
    }

    @Transactional
    public TransferRequestResponse create(CurrentUser user, UUID appealId, CreateTransferRequest request) {
        Appeal appeal = findAppeal(appealId);
        // передать можно только обращение своего подразделения — подразделение из токена
        access.checkWork(user, appeal);
        Employee employee = findEmployee(user.employeeId());
        Department source = appeal.getDepartment();
        Department target = departmentRepository.findByCode(request.departmentCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Неизвестный отдел: " + request.departmentCode()));

        if (source.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Целевой отдел должен отличаться от текущего");
        }
        if (transferRepository.existsByAppealIdAndStatus(appealId, TransferRequestStatus.PENDING)) {
            throw new IllegalStateException("По обращению уже ожидается согласование передачи");
        }

        Instant now = Instant.now();
        TransferRequest transfer = transferRepository.save(new TransferRequest(
                UUID.randomUUID(),
                appeal,
                source,
                target,
                employee,
                request.reason().trim(),
                now
        ));

        historyRepository.save(new AppealHistory(
                appeal,
                "TRANSFER_REQUESTED",
                "Запрошена передача из отдела «" + source.getName() + "» в отдел «"
                        + target.getName() + "». Причина: " + request.reason().trim(),
                employee.getFullName(),
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_TRANSFER_REQUESTED",
                appeal.getId(),
                "appeal.transfer.requested." + target.getCode().toLowerCase(Locale.ROOT),
                Map.of(
                        "fromDepartment", source.getCode(),
                        "toDepartment", target.getCode(),
                        "requestedBy", employee.getFullName()
                )
        ));

        return toResponse(transfer);
    }

    @Transactional(readOnly = true)
    public List<TransferRequestResponse> pendingForDepartment(CurrentUser user) {
        return transferRepository
                .findByTargetDepartmentCodeAndStatusOrderByCreatedAtAsc(
                        user.requireDepartment(), TransferRequestStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TransferRequestResponse review(CurrentUser user, UUID transferId, ReviewTransferRequest request) {
        TransferRequest transfer = transferRepository
                .findByIdAndStatus(transferId, TransferRequestStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Запрос передачи не найден или уже рассмотрен"));

        // роль и подразделение — из токена: согласует только руководитель принимающего подразделения
        if (!user.isSupervisor()
                || !transfer.getTargetDepartment().getCode().equals(user.requireDepartment())) {
            throw new AccessDeniedException(
                    "Рассматривать передачу может только руководитель принимающего подразделения");
        }
        Employee reviewer = findEmployee(user.employeeId());

        Appeal appeal = transfer.getAppeal();
        if (!appeal.getDepartment().getId().equals(transfer.getSourceDepartment().getId())) {
            throw new IllegalStateException("Ответственный отдел обращения уже изменился");
        }

        Instant now = Instant.now();
        String comment = request.comment().trim();
        if (request.approved()) {
            int slaHours = routingRuleRepository
                    .findFirstByDepartmentCodeOrderBySlaHoursAsc(transfer.getTargetDepartment().getCode())
                    .map(rule -> rule.getSlaHours())
                    .orElse(24);
            appeal.transferTo(
                    transfer.getTargetDepartment(),
                    now.plusSeconds(slaHours * 3600L),
                    now
            );
            transfer.approve(reviewer, comment, now);
            historyRepository.save(new AppealHistory(
                    appeal,
                    "TRANSFER_APPROVED",
                    "Руководитель отдела «" + transfer.getTargetDepartment().getName()
                            + "» согласовал передачу. Комментарий: " + comment,
                    reviewer.getFullName(),
                    now
            ));
            publishReviewEvent(transfer, reviewer, "APPROVED");
        } else {
            transfer.reject(reviewer, comment, now);
            historyRepository.save(new AppealHistory(
                    appeal,
                    "TRANSFER_REJECTED",
                    "Руководитель отдела «" + transfer.getTargetDepartment().getName()
                            + "» отклонил передачу. Комментарий: " + comment,
                    reviewer.getFullName(),
                    now
            ));
            publishReviewEvent(transfer, reviewer, "REJECTED");
        }

        return toResponse(transfer);
    }

    @Transactional(readOnly = true)
    public List<TransferRequestResponse> forAppeal(UUID appealId) {
        return transferRepository.findByAppealIdOrderByCreatedAtDesc(appealId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private void publishReviewEvent(TransferRequest transfer, Employee reviewer, String decision) {
        String suffix = decision.toLowerCase(Locale.ROOT);
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_TRANSFER_" + decision,
                transfer.getAppeal().getId(),
                "appeal.transfer." + suffix + "."
                        + transfer.getTargetDepartment().getCode().toLowerCase(Locale.ROOT),
                Map.of(
                        "fromDepartment", transfer.getSourceDepartment().getCode(),
                        "toDepartment", transfer.getTargetDepartment().getCode(),
                        "reviewedBy", reviewer.getFullName()
                )
        ));
    }

    private Appeal findAppeal(UUID id) {
        return appealRepository.findById(id)
                .orElseThrow(() -> new AppealNotFoundException(id));
    }

    private Employee findEmployee(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Сотрудник не найден в справочнике подсистемы: " + id));
    }

    private TransferRequestResponse toResponse(TransferRequest transfer) {
        return new TransferRequestResponse(
                transfer.getId(),
                transfer.getAppeal().getId(),
                transfer.getAppeal().getPublicNumber(),
                transfer.getAppeal().getSubject(),
                transfer.getAppeal().getCustomerName(),
                directoryService.toDepartment(transfer.getSourceDepartment()),
                directoryService.toDepartment(transfer.getTargetDepartment()),
                directoryService.toEmployee(transfer.getRequestedBy()),
                transfer.getReason(),
                transfer.getStatus(),
                directoryService.toEmployee(transfer.getReviewedBy()),
                transfer.getReviewComment(),
                transfer.getCreatedAt(),
                transfer.getReviewedAt()
        );
    }
}
