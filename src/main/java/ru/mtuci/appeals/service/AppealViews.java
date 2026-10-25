package ru.mtuci.appeals.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.MessageResponse;
import ru.mtuci.appeals.api.ApiModels.NormResponse;
import ru.mtuci.appeals.api.ApiModels.ProductResponse;
import ru.mtuci.appeals.api.ApiModels.SignalBadge;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.repository.AppealAttachmentRepository;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Обращение глазами API: сводка для очереди и карточка целиком. Одно место, где сущность
 * превращается в ответ, поэтому все сценарии отдают обращение одинаково.
 */
@Component
public class AppealViews {

    private final AppealRepository appealRepository;
    private final AppealAttachmentRepository attachmentRepository;
    private final AppealHistoryRepository historyRepository;
    private final AppealMessageRepository messageRepository;
    private final AttachmentService attachmentService;
    private final DirectoryService directoryService;
    private final TransferService transferService;
    private final AppealFormPolicy formPolicy;
    private final AppealChecks checks;
    private final ObjectMapper objectMapper;

    public AppealViews(AppealRepository appealRepository, AppealAttachmentRepository attachmentRepository,
                       AppealHistoryRepository historyRepository, AppealMessageRepository messageRepository,
                       AttachmentService attachmentService, DirectoryService directoryService,
                       TransferService transferService, AppealFormPolicy formPolicy, AppealChecks checks,
                       ObjectMapper objectMapper) {
        this.appealRepository = appealRepository;
        this.attachmentRepository = attachmentRepository;
        this.historyRepository = historyRepository;
        this.messageRepository = messageRepository;
        this.attachmentService = attachmentService;
        this.directoryService = directoryService;
        this.transferService = transferService;
        this.formPolicy = formPolicy;
        this.checks = checks;
        this.objectMapper = objectMapper;
    }

    public AppealSummaryResponse toSummary(Appeal appeal, boolean clientReplied, List<SignalBadge> signals) {
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
                appeal.getAssignedSubject(),
                appeal.getDeadlineAt(),
                appeal.getLegalDeadline(),
                appeal.getEscalationLevel(),
                appeal.getContractLink(),
                appeal.getOutcome(),
                clientReplied,
                signals,
                appeal.getCreatedAt(),
                appeal.getUpdatedAt()
        );
    }

    public AppealDetailResponse toDetail(Appeal appeal) {
        List<HistoryResponse> history = historyRepository.findByAppealIdOrderByCreatedAtAsc(appeal.getId())
                .stream()
                .map(item -> new HistoryResponse(
                        item.getId(),
                        item.getAction(),
                        item.getDescription(),
                        item.getActor(),
                        item.getCreatedAt(),
                        item.getStatusAfter()
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
                appeal.getAssignedSubject(),
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
                appeal.getFinalCategory(),
                appeal.getFinalSubcategory(),
                appeal.getChannel(),
                appeal.getRegisteredBy(),
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

    public DepartmentResponse toDepartment(Department department) {
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

    private Map<String, String> readDetails(String detailsJson) {
        try {
            return objectMapper.readValue(detailsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать дополнительные поля", exception);
        }
    }
}
