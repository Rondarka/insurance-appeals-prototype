package ru.mtuci.appeals.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.mtuci.appeals.domain.AppealCategory;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.domain.EmployeeRole;
import ru.mtuci.appeals.domain.TransferRequestStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApiModels {

    private ApiModels() {
    }

    public record CreateAppealRequest(
            @NotNull UUID clientId,
            @NotNull UUID contractId,
            @NotNull AppealCategory category,
            @NotBlank @Size(max = 60) String subcategory,
            @NotNull Map<String, String> details,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 5000) String description
    ) {
    }

    public record CreateTransferRequest(
            @NotBlank String departmentCode,
            @NotBlank @Size(max = 500) String reason,
            @NotNull UUID employeeId
    ) {
    }

    public record ReviewTransferRequest(
            @NotNull UUID employeeId,
            @NotNull Boolean approved,
            @NotBlank @Size(max = 500) String comment
    ) {
    }

    public record ChangeStatusRequest(
            @NotNull AppealStatus status,
            @NotBlank @Size(max = 160) String employeeName
    ) {
    }

    public record AddMessageRequest(
            @NotNull AuthorType authorType,
            @NotBlank @Size(max = 160) String authorName,
            @NotBlank @Size(max = 5000) String body
    ) {
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

    public record ClientResponse(
            UUID id,
            String fullName,
            String email,
            String phone,
            List<ContractResponse> contracts
    ) {
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
            AppealCategory category,
            String subcategory,
            String subject,
            AppealStatus status,
            AppealPriority priority,
            DepartmentResponse department,
            String assignedEmployee,
            Instant deadlineAt,
            Instant createdAt,
            Instant updatedAt
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
            Instant createdAt
    ) {
    }

    public record AttachmentResponse(
            UUID id,
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
            AppealCategory category,
            String subcategory,
            Map<String, String> details,
            String subject,
            String description,
            AppealStatus status,
            AppealPriority priority,
            DepartmentResponse department,
            String assignedEmployee,
            Instant deadlineAt,
            Instant createdAt,
            Instant updatedAt,
            List<HistoryResponse> history,
            List<MessageResponse> messages,
            List<AttachmentResponse> attachments,
            List<TransferRequestResponse> transfers
    ) {
    }

    public record EventAuditResponse(
            Long id,
            UUID eventId,
            UUID appealId,
            String eventType,
            String routingKey,
            Instant receivedAt
    ) {
    }

    public record ErrorResponse(String message, Instant timestamp) {
    }
}
