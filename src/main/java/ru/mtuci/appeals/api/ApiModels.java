package ru.mtuci.appeals.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.mtuci.appeals.domain.AppealChannel;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.ContractRole;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.domain.ContractLink;
import ru.mtuci.appeals.domain.DeadlineCounting;
import ru.mtuci.appeals.domain.EscalationLevel;
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

    /** finalCategory и finalSubcategory — итоговый тип, если клиент выбрал не тот; пусто — тип подтверждён. */
    public record ResolveAppealRequest(
            @NotNull AppealOutcome outcome,
            @NotBlank @Size(min = 20, max = 5000) String answer,
            @Size(max = 50) String finalCategory,
            @Size(max = 60) String finalSubcategory
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
            String status,
            // кем клиент приходится договору: страхователь, застрахованное лицо, выгодоприобретатель
            ContractRole clientRole
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
     * OPEN — обращения приостановлены, HALF_OPEN — пробные вызовы после паузы; для доставки
     * событий OK или DELAYED — события дольше минуты ждут отправки в брокер (pending — сколько).
     * failureRate — доля сбоев среди последних вызовов, %; -1 — вызовов пока мало или неприменимо.
     */
    public record IntegrationStatusResponse(String code, String name, String state, float failureRate, long pending) {
    }

    public record ChooseContractRequest(@NotNull UUID contractId) {
    }

    public record ClassifyRequest(@NotBlank String departmentCode) {
    }

    // ---------- настройки администратора (решение 34) ----------

    /** Правило маршрутизации и предел срока отдела по закону. subcategory GENERAL — все типы категории. */
    public record RoutingRuleResponse(String category, String categoryLabel, String subcategory, String typeLabel,
                                      String departmentCode, String departmentName, String priority, int slaHours,
                                      int maxSlaHours, String legalLimit) {
    }

    /** Тип обращения, для которого нет ни своего правила, ни правила категории: уйдёт на ручную классификацию. */
    public record UncoveredTypeResponse(String category, String categoryLabel, String type, String typeLabel) {
    }

    public record RoutingSettingsResponse(List<RoutingRuleResponse> rules, List<UncoveredTypeResponse> uncovered) {
    }

    public record SaveRoutingRuleRequest(
            @NotBlank String category,
            @NotBlank String subcategory,
            @NotBlank String departmentCode,
            @NotNull AppealPriority priority,
            @Min(1) @Max(2000) int slaHours,
            @NotBlank @Size(min = 5, max = 500) String reason
    ) {
    }

    public record NormVersionResponse(Long id, String code, String title, int termDays, String counting,
                                      int maxExtensionDays, String condition, String legalBasis,
                                      LocalDate validFrom, LocalDate validTo) {
    }

    /** Новая версия норматива — со ссылкой на акт и датой начала действия. */
    public record NewNormVersionRequest(
            @NotBlank String code,
            @Min(1) @Max(365) int termDays,
            @NotNull DeadlineCounting counting,
            @Min(0) @Max(30) int maxExtensionDays,
            @NotBlank @Size(max = 300) String legalBasis,
            @NotNull LocalDate validFrom,
            @NotBlank @Size(min = 5, max = 500) String reason
    ) {
    }

    public record SettingsAuditResponse(Long id, Instant changedAt, String actorName, String entity, String entityKey,
                                        String action, String before, String after, String reason) {
    }

    /** Отдел, куда руководитель направил обращение: само обращение ему теперь может быть не видно. */
    public record ClassifiedResponse(String departmentCode, String departmentName) {
    }

    /** Сотрудник, совершивший действие: идентификатор в IdP и имя на момент действия. */
    public record StaffResponse(String id, String fullName) {
    }

    /** Сотрудник отдела, которого можно назначить исполнителем (решение 32). */
    public record StaffMemberResponse(String id, String fullName, boolean supervisor, String departmentCode) {
    }

    public record AssignRequest(@NotBlank String subject) {
    }

    public record TransferRequestResponse(
            UUID id,
            UUID appealId,
            String appealPublicNumber,
            String appealSubject,
            String customerName,
            DepartmentResponse sourceDepartment,
            DepartmentResponse targetDepartment,
            StaffResponse requestedBy,
            String reason,
            TransferRequestStatus status,
            StaffResponse reviewedBy,
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
            String assignedSubject,
            Instant deadlineAt,
            LocalDate legalDeadline,
            EscalationLevel escalationLevel,
            ContractLink contractLink,
            AppealOutcome outcome,
            // последнее сообщение в переписке — от клиента: ход за сотрудником
            boolean clientReplied,
            // сигналы в тексте клиента: грозит жалобой, судом, требует выплату (решение 35)
            List<SignalBadge> signals,
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
            Instant createdAt,
            // статус после действия: из него лента статусов для клиента
            AppealStatus statusAfter
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
            String assignedSubject,
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
            String finalCategory,
            String finalSubcategory,
            // как поступило обращение и кто из операторов его принял (решение 38)
            AppealChannel channel,
            String registeredBy,
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

    /**
     * documents: что подсказать приложить (решение 42); подсказка, а не обязательный набор.
     * policyholderOnly: обращение оформляет только страхователь (решение 43).
     */
    public record FormAppealType(String code, String label, List<FormField> fields, List<String> documents,
                                 boolean policyholderOnly) {
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

    // ---------- сроки хранения (решение 41) ----------

    /** years: через сколько лет после ответа стираются персональные данные; due: сколько ждёт обезличивания. */
    public record RetentionResponse(int years, long anonymized, long due) {
    }

    public record RetentionRequest(@Min(1) @Max(75) int years, @NotBlank @Size(max = 500) String reason) {
    }

    // ---------- журнал доступа (решение 40) ----------

    /** action: VIEWED (открыл карточку) или FILE_OPENED (открыл файл file). */
    public record AccessLogResponse(String action, String viewerName, String viewerRole, String department,
                                    Instant accessedAt, String file) {
    }

    // ---------- оператор контакт-центра (решение 38) ----------

    /** Обращение со слов клиента: канал, как к нему обращаться, и та же форма, что в кабинете. */
    public record OperatorAppealRequest(
            @NotNull UUID counterpartyId,
            @NotNull AppealChannel channel,
            @NotBlank @Size(max = 160) String customerName,
            @NotNull @Valid CreateAppealRequest appeal
    ) {
    }

    /** Клиент, найденный по номеру договора, с договорами и обращениями: оператор отвечает на звонок о статусе. */
    public record OperatorClientResponse(UUID counterpartyId, List<ContractResponse> contracts,
                                         List<AppealSummaryResponse> appeals) {
    }

    // ---------- помощник сотрудника (решение 35) ----------

    /** risk — грозит спором или проверкой: такие обращения поднимаются в очереди. */
    public record SignalBadge(String code, String label, boolean risk) {
    }

    public record SignalResponse(String code, String label, String hint, boolean risk, String excerpt) {
    }

    /** Похоже на другой тип: каким был бы срок по закону и можно ли его продлить. */
    public record TypeHintResponse(String category, String subcategory, String label, List<String> reasons,
                                   LocalDate legalDeadline, String normTitle, String legalBasis,
                                   boolean extensionAllowed, LocalDate currentLegalDeadline, String source) {
    }

    /** Решённое обращение того же типа; имя клиента в ответе скрыто. */
    public record SimilarAppealResponse(String publicNumber, String subject, AppealOutcome outcome,
                                        LocalDate resolvedOn, long resolutionDays, String answer) {
    }

    /** engine — кто подсказывает; notice — почему подсказки дают правила, хотя выбрана модель. */
    public record AssistantAdviceResponse(String engine, String notice, List<SignalResponse> signals,
                                          TypeHintResponse typeHint, List<SimilarAppealResponse> similar,
                                          Map<String, String> decisions) {
    }

    public record DraftResponse(String text, String templateTitle, String source, String notice) {
    }

    public record AssistantFeedbackRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "TYPE_HINT|SIMILAR|DRAFT") String kind,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "ACCEPTED|REJECTED") String decision
    ) {
    }

    public record AssistantSettingsResponse(String engine, String modelName, String modelUrl, String modelState,
                                            float failureRate) {
    }

    public record AssistantSettingsRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "RULES|LOCAL_MODEL") String engine,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    // ---------- аналитика (решение 23) ----------

    /** Доля в процентах с одним знаком; база — от чего она посчитана, чтобы 100 % из 2 не путать со 100 % из 200. */
    public record Rate(long count, long of, Double percent) {

        public static Rate of(long count, long base) {
            return new Rate(count, base, base == 0 ? null : Math.round(count * 1000.0 / base) / 10.0);
        }
    }

    public record AnalyticsSummary(long received, long demo, long resolved, Rate resolvedOnTime,
                                   Double averageResolutionDays, long overdueOpen, Rate satisfied,
                                   Rate transferred, Rate repeated, Rate manuallyClassified,
                                   Rate acceptedWithoutContract, Rate typeCorrected, long pretensions,
                                   Rate pretensionsOnTime, long pretensionsOverdue) {
    }

    public record ShareItem(String code, String label, Rate rate) {
    }

    public record WeekSeries(String code, String label, long[] counts) {
    }

    public record DepartmentOnTime(String code, String name, Rate onTime, long open, long overdueOpen) {
    }

    public record TransferFlow(String from, String to, long count) {
    }

    public record TypeCorrection(String initial, String finalType, long count) {
    }

    /** Подсказки помощника: сколько показано и какую долю сотрудники приняли (решение 35). */
    public record AssistantUsage(String kind, String source, long shown, Rate accepted, long rejected) {
    }

    /** from выровнен на понедельник; product — выбранный вид страхования или null — все. */
    public record AnalyticsResponse(LocalDate from, LocalDate to, String product, AnalyticsSummary summary,
                                    List<ShareItem> products, List<ShareItem> types, List<LocalDate> weeks,
                                    List<WeekSeries> weekly, List<DepartmentOnTime> departments,
                                    List<TransferFlow> transfers, List<TypeCorrection> corrections,
                                    List<ShareItem> outcomes, List<AssistantUsage> assistant,
                                    List<ShareItem> channels) {
    }

    public record ErrorResponse(String message, Instant timestamp) {
    }
}
