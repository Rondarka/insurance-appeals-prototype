package ru.mtuci.appeals.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.ContractLink;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.integration.ContractSourceUnavailableException;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.integration.ExternalContract;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Отложенная привязка договора (решение 29). Если при регистрации учётная система договоров
 * недоступна, обращение принимается с видом страхования, который выбрал клиент. Клиент
 * известен из токена, поэтому, когда система вернётся, подсистема сама загрузит его договоры
 * и подберёт подходящий: по договору из списка, по номеру, по виду и дате происшествия.
 * Подходит один — привязывает, несколько — выбирает клиент, а если он не ответил — сотрудник.
 *
 * Сетевые вызовы — вне транзакции базы, как и при регистрации.
 */
@Service
public class ContractLinkService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);

    private final AppealRepository appealRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ContractsClient contractsClient;
    private final AppealFormPolicy formPolicy;
    private final DeadlineNormService normService;
    private final ProductionCalendar calendar;
    private final AppealAccess access;
    private final DirectoryService directoryService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final ZoneId zone;
    private final Duration maxBackoff;

    public ContractLinkService(AppealRepository appealRepository,
                               AppealHistoryRepository historyRepository,
                               ApplicationEventPublisher eventPublisher,
                               ContractsClient contractsClient,
                               AppealFormPolicy formPolicy,
                               DeadlineNormService normService,
                               ProductionCalendar calendar,
                               AppealAccess access,
                               DirectoryService directoryService,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager,
                               @Value("${appeals.timezone}") String timezone,
                               @Value("${appeals.contract-link.max-backoff}") Duration maxBackoff) {
        this.appealRepository = appealRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.contractsClient = contractsClient;
        this.formPolicy = formPolicy;
        this.normService = normService;
        this.calendar = calendar;
        this.access = access;
        this.directoryService = directoryService;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.zone = ZoneId.of(timezone);
        this.maxBackoff = maxBackoff;
    }

    /**
     * Фоновый проход: обращения, у которых подошло время попытки. Если учётная система всё ещё
     * недоступна, проход останавливается на первом же обращении — нет смысла добивать её
     * запросами по каждому, — а пауза до следующей попытки растёт: 1, 2, 4… минут, не больше часа.
     */
    @Scheduled(initialDelayString = "${appeals.contract-link.initial-delay}",
            fixedDelayString = "${appeals.contract-link.interval}")
    public void linkPending() {
        for (UUID id : appealRepository.findContractLinkDue(ContractLink.PENDING, Instant.now())) {
            try {
                attempt(id, "Подсистема");
            } catch (ContractSourceUnavailableException exception) {
                postpone(id);
                return;
            } catch (OptimisticLockingFailureException exception) {
                // обращение изменили параллельно — попробуем на следующем проходе
            }
        }
    }

    /** «Привязать сейчас» — сотрудник отдела не ждёт фоновой попытки. Сбой системы — ему 503. */
    public void linkNow(CurrentUser user, UUID id) {
        transactions.executeWithoutResult(status -> {
            Appeal appeal = find(id);
            access.checkWork(user, appeal);
            if (appeal.getContractLink() != ContractLink.PENDING) {
                throw new IllegalStateException("Договор не ждёт привязки — выберите его из списка");
            }
        });
        try {
            attempt(id, employeeName(user));
        } catch (ContractSourceUnavailableException exception) {
            postpone(id);
            throw exception;
        }
    }

    /** Договоры на выбор: подходящие по виду и дате, а если таких нет — все договоры клиента. */
    public List<ContractResponse> candidates(CurrentUser user, UUID id) {
        Request request = transactions.execute(status -> {
            Appeal appeal = find(id);
            checkChoice(user, appeal);
            return Request.of(appeal, readDetails(appeal.getDetailsJson()));
        });
        Selection selection = select(contractsClient.clientContracts(request.counterpartyId()), request);
        return (selection.matching().isEmpty() ? selection.all() : selection.matching()).stream()
                .map(ExternalContract::toSnapshot)
                .map(directoryService::toContract)
                .toList();
    }

    /** Выбор договора клиентом или сотрудником отдела — только из договоров этого клиента. */
    public void choose(CurrentUser user, UUID id, UUID contractId) {
        UUID counterpartyId = transactions.execute(status -> {
            Appeal appeal = find(id);
            checkChoice(user, appeal);
            if (appeal.getContractLink() == ContractLink.PENDING) {
                throw new IllegalStateException("Договор ещё подбирается — выбор понадобится, если подойдёт несколько");
            }
            return appeal.getCounterpartyId();
        });
        ExternalContract contract = contractsClient.contract(contractId)
                .filter(found -> found.belongsTo(counterpartyId))
                .orElseThrow(() -> new IllegalArgumentException("Договор не найден или не принадлежит клиенту"));
        String how = user.isClient() ? "по выбору клиента" : "по выбору сотрудника";
        String actor = user.isClient() ? Objects.requireNonNullElse(user.name(), "Клиент") : employeeName(user);
        transactions.executeWithoutResult(status -> {
            Appeal appeal = find(id);
            if (appeal.getContractLink() == ContractLink.LINKED) {
                throw new IllegalStateException("Договор уже привязан");
            }
            link(appeal, contract, how, actor);
        });
    }

    private void attempt(UUID id, String actor) {
        Request request = transactions.execute(status -> {
            Appeal appeal = find(id);
            return appeal.getContractLink() == ContractLink.PENDING
                    ? Request.of(appeal, readDetails(appeal.getDetailsJson()))
                    : null;
        });
        if (request == null) {
            return;
        }
        // сотрудник в «Привязать сейчас» ждёт ответа, фоновый проход — нет
        List<ExternalContract> contracts = "Подсистема".equals(actor)
                ? contractsClient.clientContractsInBackground(request.counterpartyId())
                : contractsClient.clientContracts(request.counterpartyId());
        Selection selection = select(contracts, request);
        transactions.executeWithoutResult(status -> {
            Appeal appeal = find(id);
            if (appeal.getContractLink() != ContractLink.PENDING) {
                return;
            }
            if (selection.matching().size() == 1) {
                link(appeal, selection.matching().get(0), "автоматически", actor);
            } else {
                requireChoice(appeal, selection.matching().isEmpty() ? ContractLink.NOT_FOUND : ContractLink.NEEDS_CHOICE);
            }
        });
    }

    /**
     * Какие договоры клиента подходят. Сначала — договор, который клиент успел выбрать, затем
     * номер, если он его указал. Иначе — договоры указанного вида, действовавшие на дату
     * происшествия: так отсекается прошлогодний полис. Без даты — действующие. Если сужение
     * ничего не оставило, подходят все договоры этого вида — проверки покажут расхождение.
     */
    static Selection select(List<ExternalContract> contracts, Request request) {
        if (request.contractId() != null) {
            List<ExternalContract> chosen = contracts.stream()
                    .filter(contract -> contract.contractId().equals(request.contractId()))
                    .toList();
            if (!chosen.isEmpty()) {
                return new Selection(chosen, contracts);
            }
        }
        List<ExternalContract> ofProduct = contracts.stream()
                .filter(contract -> contract.product().code().equals(request.productCode()))
                .toList();
        if (request.policyNumber() != null) {
            String wanted = normalize(request.policyNumber());
            List<ExternalContract> byNumber = contracts.stream()
                    .filter(contract -> normalize(contract.policyNumber()).equals(wanted))
                    .toList();
            if (byNumber.size() == 1) {
                return new Selection(byNumber, contracts);
            }
        }
        List<ExternalContract> narrowed = ofProduct.stream()
                .filter(contract -> request.incidentDate() != null
                        ? covers(contract, request.incidentDate())
                        : "ACTIVE".equals(contract.status()))
                .toList();
        return new Selection(narrowed.isEmpty() ? ofProduct : narrowed, contracts);
    }

    private void link(Appeal appeal, ExternalContract external, String how, String actor) {
        Instant now = Instant.now();
        ContractSnapshot contract = external.toSnapshot();
        Map<String, String> details = new HashMap<>(readDetails(appeal.getDetailsJson()));
        details.putAll(formPolicy.contractDetails(appeal.getCategory(), appeal.getSubcategory(), contract));
        String requestedProduct = appeal.getRequestedProductCode();
        appeal.linkContract(contract, writeDetails(details), now);
        historyRepository.save(new AppealHistory(appeal, "CONTRACT_LINKED",
                "Договор привязан " + how + ": " + contract.getPolicyNumber() + " · " + contract.getProductName(),
                actor, now));

        // клиент мог ошибиться видом страхования — тогда срок считается по виду договора
        if (!contract.getProductCode().equals(requestedProduct) && appeal.getDeadlineExtendedDays() == 0) {
            LocalDate registeredOn = LocalDate.ofInstant(appeal.getCreatedAt(), zone);
            DeadlineNorm norm = normService.choose(appeal.getCategory(), appeal.getSubcategory(),
                    contract.getProductCode(), details, registeredOn);
            if (appeal.getNorm() == null || !norm.getId().equals(appeal.getNorm().getId())) {
                LocalDate deadline = calendar.deadline(registeredOn, norm.getTermDays(), norm.getCounting());
                appeal.applyNorm(norm, deadline);
                historyRepository.save(new AppealHistory(appeal, "DEADLINE_SET",
                        "Срок по закону пересчитан по виду договора — до " + DAY.format(deadline) + ": "
                                + norm.getTitle().toLowerCase(Locale.ROOT) + " (" + norm.getLegalBasis() + ")",
                        "Подсистема", now));
            }
        }
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_CONTRACT_LINKED",
                appeal.getId(),
                "appeal.contract.linked",
                Map.of("productCode", contract.getProductCode(), "policyNumber", contract.getPolicyNumber())
        ));
    }

    private void requireChoice(Appeal appeal, ContractLink reason) {
        Instant now = Instant.now();
        appeal.requireContractChoice(reason, now);
        String product = formPolicy.productLabel(appeal.getRequestedProductCode());
        historyRepository.save(new AppealHistory(appeal, "CONTRACT_CHOICE_REQUIRED",
                reason == ContractLink.NEEDS_CHOICE
                        ? "Подходит несколько договоров «" + product + "» — клиенту предложено выбрать"
                        : "Договор «" + product + "» у клиента не найден — предложено выбрать из всех его договоров",
                "Подсистема", now));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_CONTRACT_CHOICE_REQUIRED",
                appeal.getId(),
                "appeal.contract.choice_required",
                Map.of("reason", reason.name())
        ));
    }

    private void postpone(UUID id) {
        try {
            transactions.executeWithoutResult(status -> {
                Appeal appeal = find(id);
                long factor = 1L << Math.min(appeal.getContractLinkAttempts(), 10);
                Duration pause = FIRST_RETRY.multipliedBy(factor);
                appeal.postponeContractLink(Instant.now().plus(pause.compareTo(maxBackoff) > 0 ? maxBackoff : pause));
            });
        } catch (OptimisticLockingFailureException exception) {
            // обращение изменили параллельно — попытка просто повторится на следующем проходе
        }
    }

    /** Выбирает договор клиент — в своём обращении, сотрудник — в обращении своего отдела. */
    private void checkChoice(CurrentUser user, Appeal appeal) {
        if (user.isClient()) {
            access.checkView(user, appeal);
        } else if (user.isEmployee()) {
            access.checkWork(user, appeal);
        } else {
            throw new AccessDeniedException("Договор выбирает клиент или сотрудник отдела");
        }
    }

    private Appeal find(UUID id) {
        return appealRepository.findById(id).orElseThrow(() -> new AppealNotFoundException(id));
    }

    private static String employeeName(CurrentUser user) {
        return Objects.requireNonNullElse(user.name(), user.subject());
    }

    private static boolean covers(ExternalContract contract, LocalDate date) {
        return !date.isBefore(contract.validFrom()) && !date.isAfter(contract.validTo())
                && (contract.terminatedOn() == null || date.isBefore(contract.terminatedOn()));
    }

    private static String normalize(String policyNumber) {
        return policyNumber == null ? "" : policyNumber.replaceAll("[\\s\\-–—]", "").toUpperCase(Locale.ROOT);
    }

    private Map<String, String> readDetails(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось прочитать атрибуты обращения", exception);
        }
    }

    private String writeDetails(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось записать атрибуты обращения", exception);
        }
    }

    /** Что известно для подбора договора: клиент, что он указал и дата происшествия. */
    record Request(UUID counterpartyId, UUID contractId, String productCode, String policyNumber,
                   LocalDate incidentDate) {

        static Request of(Appeal appeal, Map<String, String> details) {
            LocalDate incident = null;
            String value = details.get("incidentDate");
            if (value != null) {
                try {
                    incident = LocalDate.parse(value);
                } catch (DateTimeParseException ignored) {
                    // формат проверяется при регистрации; на всякий случай подбираем без даты
                }
            }
            return new Request(appeal.getCounterpartyId(), appeal.getRequestedContractId(),
                    appeal.getRequestedProductCode(), appeal.getRequestedPolicyNumber(), incident);
        }
    }

    /** matching — подходящие договоры, all — все договоры клиента. */
    record Selection(List<ExternalContract> matching, List<ExternalContract> all) {
    }
}
