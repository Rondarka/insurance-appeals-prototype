package ru.mtuci.appeals.api;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.mtuci.appeals.api.ApiModels.AddMessageRequest;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.CreateTransferRequest;
import ru.mtuci.appeals.api.ApiModels.ClientResponse;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EmployeeResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.ReviewTransferRequest;
import ru.mtuci.appeals.api.ApiModels.TransferRequestResponse;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.service.AppealService;
import ru.mtuci.appeals.service.AttachmentService;
import ru.mtuci.appeals.service.DirectoryService;
import ru.mtuci.appeals.service.TransferService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AppealController {

    private final AppealService appealService;
    private final AttachmentService attachmentService;
    private final DirectoryService directoryService;
    private final TransferService transferService;

    public AppealController(AppealService appealService,
                            AttachmentService attachmentService,
                            DirectoryService directoryService,
                            TransferService transferService) {
        this.appealService = appealService;
        this.attachmentService = attachmentService;
        this.directoryService = directoryService;
        this.transferService = transferService;
    }

    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    AppealDetailResponse create(@Valid @RequestBody CreateAppealRequest request) {
        return appealService.create(request);
    }

    @GetMapping("/appeals")
    List<AppealSummaryResponse> list(
            @RequestParam(required = false) String departmentCode,
            @RequestParam(required = false) AppealStatus status,
            @RequestParam(required = false) UUID clientId) {
        return appealService.list(departmentCode, status, clientId);
    }

    @GetMapping("/appeals/{id}")
    AppealDetailResponse get(@PathVariable UUID id) {
        return appealService.get(id);
    }

    @PostMapping("/appeals/{id}/transfer-requests")
    @ResponseStatus(HttpStatus.CREATED)
    TransferRequestResponse requestTransfer(
            @PathVariable UUID id,
            @Valid @RequestBody CreateTransferRequest request) {
        return transferService.create(id, request);
    }

    @GetMapping("/transfer-requests")
    List<TransferRequestResponse> transferRequests(
            @RequestParam String targetDepartmentCode) {
        return transferService.pendingForDepartment(targetDepartmentCode);
    }

    @PostMapping("/transfer-requests/{id}/review")
    TransferRequestResponse reviewTransfer(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewTransferRequest request) {
        return transferService.review(id, request);
    }

    @PostMapping("/appeals/{id}/status")
    AppealDetailResponse changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatusRequest request) {
        return appealService.changeStatus(id, request);
    }

    @PostMapping("/appeals/{id}/messages")
    AppealDetailResponse addMessage(
            @PathVariable UUID id,
            @Valid @RequestBody AddMessageRequest request) {
        return appealService.addMessage(id, request);
    }

    @PostMapping(
            value = "/appeals/{id}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    List<AttachmentResponse> uploadAttachments(
            @PathVariable UUID id,
            @RequestParam("files") List<MultipartFile> files) {
        return attachmentService.store(id, files);
    }

    @GetMapping("/attachments/{id}")
    ResponseEntity<Resource> downloadAttachment(@PathVariable UUID id) {
        AttachmentService.AttachmentDownload download = attachmentService.download(id);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.attachment().getContentType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.attachment().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.attachment().getOriginalName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(download.resource());
    }

    @GetMapping("/departments")
    List<DepartmentResponse> departments() {
        return appealService.departments();
    }

    @GetMapping("/clients")
    List<ClientResponse> clients() {
        return directoryService.clients();
    }

    @GetMapping("/employees")
    List<EmployeeResponse> employees() {
        return directoryService.employees();
    }

    @GetMapping("/events")
    List<EventAuditResponse> events() {
        return appealService.recentEvents();
    }
}
