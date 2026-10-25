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
import ru.mtuci.appeals.api.ApiModels.ProductResponse;
import ru.mtuci.appeals.api.ApiModels.ChooseContractRequest;
import ru.mtuci.appeals.api.ApiModels.ClassifiedResponse;
import ru.mtuci.appeals.api.ApiModels.ClassifyRequest;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.ExtendDeadlineRequest;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AssignRequest;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ContractTermsResponse;
import ru.mtuci.appeals.api.ApiModels.RelatedAppealResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.CreateTransferRequest;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EventAuditResponse;
import ru.mtuci.appeals.api.ApiModels.FormSchemaResponse;
import ru.mtuci.appeals.api.ApiModels.AssistantSettingsResponse;
import ru.mtuci.appeals.api.ApiModels.IntegrationStatusResponse;
import ru.mtuci.appeals.assistant.AssistantEngine;
import ru.mtuci.appeals.assistant.AssistantService;
import ru.mtuci.appeals.api.ApiModels.MeResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.api.ApiModels.StaffMemberResponse;
import ru.mtuci.appeals.api.ApiModels.ReviewTransferRequest;
import ru.mtuci.appeals.api.ApiModels.TransferRequestResponse;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.ContractRole;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.messaging.OutboxRelay;
import ru.mtuci.appeals.security.CurrentUser;
import ru.mtuci.appeals.service.AppealFormPolicy;
import ru.mtuci.appeals.service.AccessLog;
import ru.mtuci.appeals.service.AppealRegistrationService;
import ru.mtuci.appeals.service.AppealService;
import ru.mtuci.appeals.service.AppealWorkflowService;
import ru.mtuci.appeals.service.CorrespondenceService;
import ru.mtuci.appeals.service.AttachmentService;
import ru.mtuci.appeals.service.ClassificationService;
import ru.mtuci.appeals.service.StaffService;
import ru.mtuci.appeals.service.ContractLinkService;
import ru.mtuci.appeals.service.DirectoryService;
import ru.mtuci.appeals.service.TransferService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
    private final AppealRegistrationService registration;
    private final AppealWorkflowService workflow;
    private final CorrespondenceService correspondence;
    private final AttachmentService attachmentService;
    private final DirectoryService directoryService;
    private final TransferService transferService;
    private final AppealFormPolicy formPolicy;
    private final ContractLinkService contractLinkService;
    private final ContractsClient contractsClient;
    private final OutboxRelay outboxRelay;
    private final ClassificationService classificationService;
    private final StaffService staffService;
    private final AssistantService assistantService;
    private final AccessLog accessLog;

    public AppealController(AppealService appealService,
                            AppealRegistrationService registration,
                            AppealWorkflowService workflow,
                            CorrespondenceService correspondence,
                            AttachmentService attachmentService,
                            DirectoryService directoryService,
                            TransferService transferService,
                            AppealFormPolicy formPolicy,
                            ContractLinkService contractLinkService,
                            ContractsClient contractsClient,
                            OutboxRelay outboxRelay,
                            ClassificationService classificationService,
                            StaffService staffService,
                            AssistantService assistantService,
                            AccessLog accessLog) {
        this.appealService = appealService;
        this.registration = registration;
        this.workflow = workflow;
        this.correspondence = correspondence;
        this.attachmentService = attachmentService;
        this.directoryService = directoryService;
        this.transferService = transferService;
        this.formPolicy = formPolicy;
        this.contractLinkService = contractLinkService;
        this.contractsClient = contractsClient;
        this.outboxRelay = outboxRelay;
        this.classificationService = classificationService;
        this.staffService = staffService;
        this.assistantService = assistantService;
        this.accessLog = accessLog;
    }

    @GetMapping("/me")
    MeResponse me(CurrentUser user) {
        return directoryService.me(user);
    }

    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    AppealDetailResponse create(CurrentUser user, @Valid @RequestBody CreateAppealRequest request) {
        return registration.create(user, request);
    }

    /** departmentCode учитывается только у администратора: остальным очередь задаёт токен. */
    @GetMapping("/appeals")
    List<AppealSummaryResponse> list(CurrentUser user,
                                     @RequestParam(required = false) AppealStatus status,
                                     @RequestParam(required = false) String departmentCode) {
        return appealService.list(user, status, departmentCode);
    }

    @GetMapping("/appeals/{id}")
    AppealDetailResponse get(CurrentUser user, @PathVariable UUID id) {
        AppealDetailResponse appeal = appealService.get(user, id);
        // в журнал доступа пишется, только если обращение действительно показано
        accessLog.viewed(user, id);
        return appeal;
    }

    /** Условия договора обращения — из учётной системы, на момент запроса. */
    @GetMapping("/appeals/{id}/contract")
    ContractTermsResponse contractTerms(CurrentUser user, @PathVariable UUID id) {
        return appealService.contractTerms(user, id);
    }

    /** Другие обращения того же клиента. */
    @GetMapping("/appeals/{id}/related")
    List<RelatedAppealResponse> related(CurrentUser user, @PathVariable UUID id) {
        return appealService.related(user, id);
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
        return workflow.changeStatus(user, id, request);
    }

    @PostMapping("/appeals/{id}/resolve")
    AppealDetailResponse resolve(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ResolveAppealRequest request) {
        return workflow.resolve(user, id, request);
    }

    @GetMapping("/integrations")
    List<IntegrationStatusResponse> integrations() {
        var circuit = contractsClient.circuitBreaker();
        OutboxRelay.Backlog backlog = outboxRelay.backlog();
        boolean delayed = backlog.oldest() != null && backlog.oldest().isBefore(Instant.now().minusSeconds(60));
        List<IntegrationStatusResponse> integrations = new ArrayList<>(List.of(
                new IntegrationStatusResponse("CONTRACTS", "Учётная система договоров",
                        circuit.getState().name(), circuit.getMetrics().getFailureRate(), 0),
                new IntegrationStatusResponse("EVENTS", "Доставка событий в RabbitMQ",
                        delayed ? "DELAYED" : "OK", -1, backlog.pending())));
        AssistantSettingsResponse assistant = assistantService.status();
        if (AssistantEngine.LOCAL_MODEL.equals(assistant.engine())) {
            integrations.add(new IntegrationStatusResponse("ASSISTANT", "Локальная модель помощника",
                    assistant.modelState(), assistant.failureRate(), 0));
        }
        return integrations;
    }

    /** Виды страхования — когда договоры клиента загрузить не удалось. */
    @GetMapping("/products")
    List<ProductResponse> products() {
        return formPolicy.products();
    }

    /** Договоры на выбор, если подходит несколько или ни одного (решение 29). */
    @GetMapping("/appeals/{id}/contract-candidates")
    List<ContractResponse> contractCandidates(CurrentUser user, @PathVariable UUID id) {
        return contractLinkService.candidates(user, id);
    }

    @PostMapping("/appeals/{id}/contract")
    AppealDetailResponse chooseContract(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ChooseContractRequest request) {
        contractLinkService.choose(user, id, request.contractId());
        return appealService.get(user, id);
    }

    /** Сотрудник не ждёт фоновой попытки: учётная система уже работает. */
    @PostMapping("/appeals/{id}/contract/link")
    AppealDetailResponse linkContractNow(CurrentUser user, @PathVariable UUID id) {
        contractLinkService.linkNow(user, id);
        return appealService.get(user, id);
    }

    /** Сотрудники своего отдела — для назначения исполнителя (решение 32). */
    @GetMapping("/staff")
    List<StaffMemberResponse> staff(CurrentUser user) {
        return staffService.department(user.requireDepartment());
    }

    @PostMapping("/appeals/{id}/assign")
    AppealDetailResponse assign(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return workflow.assign(user, id, request.subject());
    }

    /** Ручная классификация: отдел выбирает руководитель (решение 31). */
    @PostMapping("/appeals/{id}/classify")
    ClassifiedResponse classify(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody ClassifyRequest request) {
        return classificationService.classify(user, id, request.departmentCode());
    }

    @PostMapping("/appeals/{id}/extend-deadline")
    AppealDetailResponse extendDeadline(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ExtendDeadlineRequest request) {
        return workflow.extendDeadline(user, id, request);
    }

    @PostMapping(value = "/appeals/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    AppealDetailResponse addMessage(
            CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody AddMessageRequest request) {
        return correspondence.addMessage(user, id, request.body(), List.of());
    }

    /** То же сообщение, но с файлами: текст можно не писать, если приложен файл. */
    @PostMapping(value = "/appeals/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AppealDetailResponse addMessageWithFiles(
            CurrentUser user,
            @PathVariable UUID id,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return correspondence.addMessage(user, id, body, files);
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
        accessLog.fileOpened(user, download.attachment().getAppeal().getId(), id);
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
    FormSchemaResponse formSchema(@RequestParam(required = false) String productCode,
                                  @RequestParam(required = false) ContractRole clientRole) {
        return formPolicy.schema(productCode, clientRole);
    }

    @GetMapping("/events")
    List<EventAuditResponse> events() {
        return appealService.recentEvents();
    }
}
