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
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.FormSchemaResponse;
import ru.mtuci.appeals.api.ApiModels.MeResponse;
import ru.mtuci.appeals.api.ApiModels.ReviewTransferRequest;
import ru.mtuci.appeals.api.ApiModels.TransferRequestResponse;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.security.CurrentUser;
import ru.mtuci.appeals.service.AppealFormPolicy;
import ru.mtuci.appeals.service.AppealService;
import ru.mtuci.appeals.service.AttachmentService;
import ru.mtuci.appeals.service.DirectoryService;
import ru.mtuci.appeals.service.TransferService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Кто делает запрос, контроллер узнаёт из токена (параметр CurrentUser), а не из тела
 * запроса: идентификаторов клиента и сотрудника в запросах нет.
 */
@RestController
@RequestMapping("/api")
public class AppealController {

    private final AppealService appealService;
    private final AttachmentService attachmentService;
    private final DirectoryService directoryService;
    private final TransferService transferService;
    private final AppealFormPolicy formPolicy;

    public AppealController(AppealService appealService,
                            AttachmentService attachmentService,
                            DirectoryService directoryService,
                            TransferService transferService,
                            AppealFormPolicy formPolicy) {
        this.appealService = appealService;
        this.attachmentService = attachmentService;
        this.directoryService = directoryService;
        this.transferService = transferService;
        this.formPolicy = formPolicy;
    }

    @GetMapping("/me")
    MeResponse me(CurrentUser user) {
        return directoryService.me(user);
    }

    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    AppealDetailResponse create(CurrentUser user, @Valid @RequestBody CreateAppealRequest request) {
        return appealService.create(user, request);
    }

    @GetMapping("/appeals")
    List<AppealSummaryResponse> list(CurrentUser user, @RequestParam(required = false) AppealStatus status) {
        return appealService.list(user, status);
    }

    @GetMapping("/appeals/{id}")
    AppealDetailResponse get(CurrentUser user, @PathVariable UUID id) {
        return appealService.get(user, id);
    }

    @PostMapping("/appeals/{id}/transfer-requests")
    @ResponseStatus(HttpStatus.CREATED)
    TransferRequestResponse requestTransfer(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody CreateTransferRequest request) {
        return transferService.create(user, id, request);
    }

    /** Запросы на передачу в подразделение руководителя — подразделение из токена. */
    @GetMapping("/transfer-requests")
    List<TransferRequestResponse> transferRequests(CurrentUser user) {
        return transferService.pendingForDepartment(user);
    }

    @PostMapping("/transfer-requests/{id}/review")
    TransferRequestResponse reviewTransfer(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ReviewTransferRequest request) {
        return transferService.review(user, id, request);
    }

    @PostMapping("/appeals/{id}/status")
    AppealDetailResponse changeStatus(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatusRequest request) {
        return appealService.changeStatus(user, id, request);
    }

    @PostMapping("/appeals/{id}/messages")
    AppealDetailResponse addMessage(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody AddMessageRequest request) {
        return appealService.addMessage(user, id, request);
    }

    @PostMapping(
            value = "/appeals/{id}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    List<AttachmentResponse> uploadAttachments(
            CurrentUser user,
            @PathVariable UUID id,
            @RequestParam("files") List<MultipartFile> files) {
        return attachmentService.store(user, id, files);
    }

    @GetMapping("/attachments/{id}")
    ResponseEntity<Resource> downloadAttachment(CurrentUser user, @PathVariable UUID id) {
        AttachmentService.AttachmentDownload download = attachmentService.download(user, id);
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

    /** Без productCode — полная схема, с ним — только типы, допустимые для продукта договора. */
    @GetMapping("/form-schema")
    FormSchemaResponse formSchema(@RequestParam(required = false) String productCode) {
        return formPolicy.schema(productCode);
    }

    @GetMapping("/events")
    List<EventAuditResponse> events() {
        return appealService.recentEvents();
    }
}
