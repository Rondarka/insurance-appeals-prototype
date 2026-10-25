package ru.mtuci.appeals.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.OperatorAppealRequest;
import ru.mtuci.appeals.assistant.SignalDetector;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealChannel;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.integration.ContractSourceUnavailableException;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Регистрация обращения: клиентом в кабинете или оператором контакт-центра со слов клиента
 * (решение 38). Договор сверяется с клиентом, срок по закону считается по нормативу на день
 * регистрации, в тексте ищутся сигналы, наружу уходит событие для маршрутизации.
 */
@Service
public class AppealRegistrationService {

    private static final DateTimeFormatter NUMBER_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AppealRepository appealRepository;
    private final ContractsClient contractsClient;
    private final TransactionTemplate transactionTemplate;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AppealFormPolicy formPolicy;
    private final ObjectMapper objectMapper;
    private final DeadlineNormService normService;
    private final ProductionCalendar calendar;
    private final SignalDetector signals;
    private final AppealViews views;
    private final ZoneId zone;

    public AppealRegistrationService(AppealRepository appealRepository, ContractsClient contractsClient,
                                     PlatformTransactionManager transactionManager,
                                     AppealHistoryRepository historyRepository, ApplicationEventPublisher eventPublisher,
                                     AppealFormPolicy formPolicy, ObjectMapper objectMapper,
                                     DeadlineNormService normService, ProductionCalendar calendar,
                                     SignalDetector signals, AppealViews views,
                                     @Value("${appeals.timezone}") String timezone) {
        this.appealRepository = appealRepository;
        this.contractsClient = contractsClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.formPolicy = formPolicy;
        this.objectMapper = objectMapper;
        this.normService = normService;
        this.calendar = calendar;
        this.signals = signals;
        this.views = views;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * Регистрация обращения. Договор запрашивается у учётной системы до начала транзакции:
     * сетевой вызов не должен держать соединение с базой. Транзакция охватывает только запись.
     */
    public AppealDetailResponse create(CurrentUser user, CreateAppealRequest request) {
        // имя и почта берутся из токена на момент регистрации, как и снимок договора
        return create(new Applicant(user.requireCounterparty(), Objects.requireNonNullElse(user.name(), "Клиент"),
                Objects.requireNonNullElse(user.email(), ""), AppealChannel.CABINET, null), request);
    }

    /**
     * Оператор контакт-центра регистрирует обращение со слов клиента, по телефону или в офисе
     * (решение 38). Дальше всё как у обращения из кабинета: договор сверяется с клиентом,
     * срок по закону, маршрутизация, уведомление клиенту.
     */
    public AppealDetailResponse createByOperator(CurrentUser operator, OperatorAppealRequest request) {
        if (request.channel() == AppealChannel.CABINET) {
            throw new IllegalArgumentException("Оператор принимает обращения по телефону или в офисе");
        }
        return create(new Applicant(request.counterpartyId(), request.customerName().trim(), "", request.channel(),
                Objects.requireNonNullElse(operator.name(), operator.subject())), request.appeal());
    }

    /** Кто подаёт обращение: клиент сам или оператор с его слов. */
    private record Applicant(UUID counterpartyId, String name, String email, AppealChannel channel, String operator) {
    }

    private AppealDetailResponse create(Applicant applicant, CreateAppealRequest request) {
        UUID counterpartyId = applicant.counterpartyId();
        ContractSnapshot found = null;
        if (request.contractId() != null) {
            try {
                // «не найден» и «чужой» неразличимы для клиента: не раскрываем, существует ли договор
                found = contractsClient.contract(request.contractId())
                        .filter(contract -> contract.belongsTo(counterpartyId))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Договор не найден или не принадлежит выбранному клиенту"))
                        .toSnapshot(counterpartyId);
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

        return transactionTemplate.execute(transaction -> register(applicant, request, contract, productCode, details));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private AppealDetailResponse register(Applicant applicant, CreateAppealRequest request, ContractSnapshot contract,
                                          String productCode, Map<String, String> details) {
        String customerName = applicant.name();
        String customerEmail = applicant.email();
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        String publicNumber = "APP-" + NUMBER_DATE.format(now) + "-"
                + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);

        Appeal appeal = new Appeal(
                id,
                publicNumber,
                applicant.counterpartyId(),
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
        if (applicant.operator() != null) {
            appeal.registeredBy(applicant.channel(), applicant.operator());
        }
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
                applicant.operator() == null
                        ? "Клиент зарегистрировал обращение. Ожидается автоматическая маршрутизация."
                        : "Оператор принял обращение со слов клиента " + applicant.channel().label()
                        + ". Ожидается автоматическая маршрутизация.",
                applicant.operator() == null ? customerName : applicant.operator(),
                now
        ));
        if (contract == null) {
            historyRepository.save(new AppealHistory(
                    appeal,
                    "CONTRACT_PENDING",
                    "Учётная система договоров недоступна, договор «" + formPolicy.productLabel(productCode)
                            + "» будет подобран автоматически, когда она заработает",
                    "Подсистема",
                    now
            ));
        }
        // сигналы ссылаются на обращение: оно должно уже быть в базе
        appealRepository.flush();
        signals.record(appeal.getId(), appeal.getSubject() + "\n" + appeal.getDescription(), now);
        historyRepository.save(new AppealHistory(
                appeal,
                "DEADLINE_SET",
                "Срок ответа по закону: до " + legalDeadline.format(DAY) + ": " + norm.getTitle().toLowerCase(Locale.ROOT)
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

        return views.toDetail(appeal);
    }

    private String writeDetails(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Не удалось обработать дополнительные поля", exception);
        }
    }
}
