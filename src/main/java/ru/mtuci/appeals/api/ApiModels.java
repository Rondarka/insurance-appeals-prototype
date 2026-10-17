package ru.mtuci.appeals.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.domain.ContractLink;
import ru.mtuci.appeals.domain.DeadlineCounting;
import ru.mtuci.appeals.domain.EscalationLevel;
import ru.mtuci.appeals.domain.EmployeeRole;
import ru.mtuci.appeals.domain.TransferRequestStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiModels {

    private ApiModels() {
    }

    /** Кто клиент, подсистема узнаёт из токена, поэтому идентификатора клиента в запросе нет. */
    /**
     * contractId — договор из списка. Если список договоров загрузить не удалось, клиент
     * указывает вид страхования (productCode) и, по желанию, номер договора (решение 29).
     * productCode передаётся и вместе с договором: на случай, если учётная система
     * откажет в момент отправки.
     */
    public record CreateAppealRequest(
            UUID contractId,
            @Size(max = 30) String productCode,
            @Size(max = 80) String policyNumber,
            @NotBlank @Size(max = 50) String category,
            @NotBlank @Size(max = 60) String subcategory,
            @NotNull Map<String, String> details,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 5000) String description
    ) {
    }

    public record CreateTransferRequest(
            @NotBlank String departmentCode,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record ReviewTransferRequest(
            @NotNull Boolean approved,
            @NotBlank @Size(max = 500) String comment
    ) {
    }

    public record ChangeStatusRequest(@NotNull AppealStatus status) {
    }

    /** Ответ клиенту по существу: результат рассмотрения и обоснование (4015-1, ст. 6.2 п. 6). */
    /** Продление срока по закону руководителем: на сколько рабочих дней и почему. */
    public record ExtendDeadlineRequest(
            @Min(1) @Max(30) int days,
            @NotBlank @Size(min = 10, max = 1000) String reason
    ) {
    }

    public record ResolveAppealRequest(
            @NotNull AppealOutcome outcome,
            @NotBlank @Size(min = 20, max = 5000) String answer
    ) {
    }

    /** Автор сообщения — тот, чей токен: клиент или сотрудник. */
    public record AddMessageRequest(@NotBlank @Size(max = 5000) String body) {
    }

    public record DepartmentResponse(Long id, String code, String name, String description) {
    }

    /** insuranceType — название продукта для показа, productCode — его код из учётной системы. */
    public record ContractResponse(
            UUID id,
            String policyNumber,
            String productCode,
            String insuranceType,
            String insuredObject,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDate terminatedOn,
            String status
    ) {
    }

    /**
     * Условия договора — по запросу сотрудника из учётной системы, всегда актуальные.
     * current — договор сейчас; в обращении хранится его снимок на момент регистрации.
     */
    public record ContractTermsResponse(
            ContractResponse current,
            BigDecimal insuredSum,
            BigDecimal deductible,
            BigDecimal premium,
            String paymentStatus,
            List<CoverageResponse> coverages
    ) {
    }

    public record CoverageResponse(String code, String name) {
    }

    /** Проверка, сделанная подсистемой: level — OK или WARNING. */
    public record CheckResponse(String code, String level, String message) {
    }

    /** Другое обращение того же клиента. canOpen — можно ли открыть его карточку. */
    public record RelatedAppealResponse(
            UUID id,
            String publicNumber,
            String subject,
            String category,
            String subcategory,
            AppealStatus status,
            AppealOutcome outcome,
            String departmentName,
            boolean sameContract,
            boolean canOpen,
            Instant createdAt
    ) {
    }

    /**
     * Текущий пользователь по токену. У клиента — договоры из учётной системы,
     * у сотрудника — подразделение.
     */
    public record MeResponse(
            String subject,
            String name,
            String email,
            List<String> roles,
            UUID counterpartyId,
            DepartmentResponse department,
            List<ContractResponse> contracts,
            boolean contractsUnavailable
    ) {
    }

    public record ProductResponse(String code, String label) {
    }

    /**
     * Состояние внешней системы глазами подсистемы (решение 30): CLOSED — работает,
     * OPEN — обращения приостановлены, HALF_OPEN — пробные вызовы после паузы.
     * failureRate — доля сбоев среди последних вызовов, %; -1 — вызовов пока мало.
     */
    public record IntegrationStatusResponse(String code, String name, String state, float failureRate) {
    }

    public record ChooseContractRequest(@NotNull UUID contractId) {
    }

    public record EmployeeResponse(
            UUID id,
            String fullName,
            EmployeeRole role,
            DepartmentResponse department
    ) {
    }

    public record TransferRequestResponse(
            UUID id,
            UUID appealId,
            String appealPublicNumber,
            String appealSubject,
            String customerName,
            DepartmentResponse sourceDepartment,
            DepartmentResponse targetDepartment,
            EmployeeResponse requestedBy,
            String reason,
            TransferRequestStatus status,
            EmployeeResponse reviewedBy,
            String reviewComment,
            Instant createdAt,
            Instant reviewedAt
    ) {
    }

    public record AppealSummaryResponse(
            UUID id,
            String publicNumber,
            String customerName,
            String category,
            String subcategory,
            String subject,
            AppealStatus status,
            AppealPriority priority,
            DepartmentResponse department,
            String assignedEmployee,
            Instant deadlineAt,
            LocalDate legalDeadline,
            EscalationLevel escalationLevel,
            ContractLink contractLink,
            AppealOutcome outcome,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** Применённый норматив срока: почему у обращения такой срок. */
    public record NormResponse(
            String code,
            String title,
            int termDays,
            DeadlineCounting counting,
            int maxExtensionDays,
            String legalBasis
    ) {
    }

    public record HistoryResponse(
            Long id,
            String action,
            String description,
            String actor,
            Instant createdAt
    ) {
    }

    public record MessageResponse(
            Long id,
            AuthorType authorType,
            String authorName,
            String body,
            Instant createdAt,
            List<AttachmentResponse> attachments
    ) {
    }

    /** messageId — сообщение, с которым пришёл файл; null — файл из формы обращения. */
    public record AttachmentResponse(
            UUID id,
            Long messageId,
            String originalName,
            String contentType,
            long sizeBytes,
            Instant uploadedAt,
            String downloadUrl
    ) {
    }

    public record AppealDetailResponse(
            UUID id,
            String publicNumber,
            String customerName,
            String customerEmail,
            ContractResponse contract,
            String category,
            String subcategory,
            Map<String, String> details,
            String subject,
            String description,
            AppealStatus status,
            AppealPriority priority,
            DepartmentResponse department,
            String assignedEmployee,
            Instant deadlineAt,
            LocalDate legalDeadline,
            NormResponse norm,
            int deadlineExtendedDays,
            String deadlineExtensionReason,
            EscalationLevel escalationLevel,
            ContractLink contractLink,
            ProductResponse requestedProduct,
            String requestedPolicyNumber,
            AppealOutcome outcome,
            Instant resolvedAt,
            Instant createdAt,
            Instant updatedAt,
            List<HistoryResponse> history,
            List<MessageResponse> messages,
            List<AttachmentResponse> attachments,
            List<TransferRequestResponse> transfers,
            List<AppealStatus> nextStatuses,
            List<CheckResponse> checks
    ) {
    }

    public record EventAuditResponse(
            Long id,
            UUID eventId,
            UUID appealId,
            String appealNumber,
            String eventType,
            String routingKey,
            Instant receivedAt
    ) {
    }

    /** Схема формы: что спросить у клиента. Как это показать, решает интерфейс (решение 24). */
    public record FormSchemaResponse(List<FormCategory> categories) {
    }

    public record FormCategory(String code, String label, List<FormAppealType> types) {
    }

    public record FormAppealType(String code, String label, List<FormField> fields) {
    }

    /**
     * inputType — TEXT, DATE или NUMBER. contractAttribute заполнен, если значение
     * подставляет сервер из договора: такое поле клиенту не показывают.
     */
    public record FormField(
            String code,
            String label,
            String inputType,
            String hint,
            boolean required,
            String contractAttribute
    ) {
    }

    public record ErrorResponse(String message, Instant timestamp) {
    }
}
