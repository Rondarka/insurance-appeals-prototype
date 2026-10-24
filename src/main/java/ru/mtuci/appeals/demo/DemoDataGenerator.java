package ru.mtuci.appeals.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.FormAppealType;
import ru.mtuci.appeals.api.ApiModels.FormCategory;
import ru.mtuci.appeals.api.ApiModels.FormField;
import ru.mtuci.appeals.assistant.SignalDetector;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealChannel;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealMessage;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.RoutingRule;
import ru.mtuci.appeals.domain.TransferRequest;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.repository.RoutingRuleRepository;
import ru.mtuci.appeals.repository.TransferRequestRepository;
import ru.mtuci.appeals.service.AppealFormPolicy;
import ru.mtuci.appeals.service.DeadlineNormService;
import ru.mtuci.appeals.service.ProductionCalendar;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Генератор демонстрационных обращений (решение 33). Обращения проходят тот же путь, что
 * настоящие, и на тех же правилах: форма проверяется схемой, срок по закону выбирает
 * справочник нормативов по производственному календарю, статусы меняются только разрешёнными
 * переходами. Даты — за последние месяцы; шаги, которые к «сейчас» ещё не наступили, не
 * выполняются, поэтому свежие обращения остаются в работе. События наружу не публикуются:
 * это прошлое, существующей системе о нём сообщать нечего.
 *
 * Клиенты и их договоры — вымышленные, сотрудники — из демонстрационного реалма и несколько
 * демонстрационных. Генерация воспроизводима: при том же зерне получаются те же данные.
 */
@Component
public class DemoDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(DemoDataGenerator.class);
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final Map<String, String> PRODUCTS = Map.of("CASCO", "КАСКО", "OSAGO", "ОСАГО",
            "MORTGAGE", "Ипотечное страхование", "PROPERTY", "Страхование имущества", "LIFE", "Страхование жизни");
    private static final Map<String, String> POLICY_PREFIX = Map.of("CASCO", "КАСКО", "OSAGO", "ОСАГО",
            "MORTGAGE", "ИП", "PROPERTY", "ИМ", "LIFE", "СЖ");
    private static final Map<String, Integer> PRODUCT_WEIGHTS = Map.of("CASCO", 30, "OSAGO", 30, "MORTGAGE", 15,
            "PROPERTY", 15, "LIFE", 10);
    private static final Map<String, Integer> TYPE_WEIGHTS = Map.ofEntries(
            // доли видов обращений: страховые случаи ≈ 38 %, договоры ≈ 15 %, ипотека ≈ 12 %, расчёты и жалобы ≈ 11 %
            Map.entry("CLAIM/AUTO", 22), Map.entry("CLAIM/PROPERTY", 9), Map.entry("CLAIM/HEALTH", 4),
            Map.entry("MORTGAGE/CLAIM_EVENT", 3), Map.entry("MORTGAGE/RENEWAL", 4), Map.entry("MORTGAGE/DOCUMENTS", 2),
            Map.entry("MORTGAGE/DATA_CHANGE", 1), Map.entry("MORTGAGE/PAYMENT", 1),
            Map.entry("POLICY/COPY", 3), Map.entry("POLICY/DATA_CHANGE", 6), Map.entry("POLICY/TERMINATION", 5),
            Map.entry("PAYMENT/REFUND", 5), Map.entry("PAYMENT/INCORRECT_AMOUNT", 2), Map.entry("PAYMENT/PAYMENT_STATUS", 3),
            Map.entry("COMPLAINT/PRETENSION", 6), Map.entry("COMPLAINT/DEADLINE", 1), Map.entry("COMPLAINT/EMPLOYEE", 1),
            Map.entry("COMPLAINT/SERVICE_QUALITY", 2), Map.entry("TECHNICAL/LOGIN", 4),
            Map.entry("TECHNICAL/DOCUMENT_UPLOAD", 2), Map.entry("TECHNICAL/PERSONAL_ACCOUNT", 3),
            Map.entry("OTHER/GENERAL", 2));

    /** Сотрудник отдела: из демонстрационного реалма (идентификатор IdP) или демонстрационный. */
    record Staff(String subject, String name, boolean supervisor) {
    }

    private static final Map<String, List<Staff>> STAFF = Map.of(
            "SUPPORT", List.of(new Staff("31111111-1111-1111-1111-111111111111", "Елена Соколова", false),
                    new Staff("demo-support-1", "Никита Громов", false),
                    new Staff("32222222-2222-2222-2222-222222222222", "Андрей Волков", true)),
            "CLAIMS", List.of(new Staff("34444444-4444-4444-4444-444444444444", "Сергей Лебедев", false),
                    new Staff("demo-claims-1", "Анастасия Кравец", false),
                    new Staff("demo-claims-2", "Олег Титов", false),
                    new Staff("33333333-3333-3333-3333-333333333333", "Марина Орлова", true)),
            "MORTGAGE", List.of(new Staff("demo-mortgage-1", "Вера Лаврова", false),
                    new Staff("demo-mortgage-2", "Игорь Сафонов", false),
                    new Staff("35555555-5555-5555-5555-555555555555", "Павел Кузнецов", true)),
            "PAYMENTS", List.of(new Staff("demo-payments-1", "Татьяна Зуева", false),
                    new Staff("demo-payments-2", "Роман Ершов", false),
                    new Staff("36666666-6666-6666-6666-666666666666", "Ольга Морозова", true)),
            "POLICY", List.of(new Staff("demo-policy-1", "Дарья Миронова", false),
                    new Staff("demo-policy-2", "Кирилл Беляев", false),
                    new Staff("37777777-7777-7777-7777-777777777777", "Ирина Белова", true)),
            "QUALITY", List.of(new Staff("demo-quality-1", "Светлана Котова", false),
                    new Staff("demo-quality-2", "Максим Ильин", false),
                    new Staff("38888888-8888-8888-8888-888888888888", "Дмитрий Фомин", true)));

    /** Типичные ошибки выбора типа клиентом: что выбрал → что на самом деле. */
    private static final Map<String, String> CORRECTIONS = Map.of(
            "COMPLAINT/SERVICE_QUALITY", "COMPLAINT/PRETENSION",
            "TECHNICAL/PERSONAL_ACCOUNT", "PAYMENT/PAYMENT_STATUS",
            "OTHER/GENERAL", "POLICY/DATA_CHANGE",
            "POLICY/DATA_CHANGE", "POLICY/TERMINATION",
            "PAYMENT/INCORRECT_AMOUNT", "COMPLAINT/PRETENSION");

    /** Типичные ошибки маршрутизации: откуда и куда передают и почему. */
    private static final Map<String, List<String>> TRANSFERS = Map.of(
            "SUPPORT", List.of("PAYMENTS", "Клиент спрашивает о возврате платежа — компетенция отдела расчётов"),
            "POLICY", List.of("CLAIMS", "В обращении заявлено о страховом случае"),
            "PAYMENTS", List.of("POLICY", "Возврат связан с расторжением договора — сначала нужно оформить расторжение"));

    private final AppealRepository appeals;
    private final AppealHistoryRepository history;
    private final AppealMessageRepository messages;
    private final TransferRequestRepository transfers;
    private final DepartmentRepository departments;
    private final RoutingRuleRepository rules;
    private final AppealFormPolicy formPolicy;
    private final DeadlineNormService normService;
    private final ProductionCalendar calendar;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;
    private final SignalDetector signals;
    private final TransactionTemplate transactions;
    private final ZoneId zone;

    public DemoDataGenerator(AppealRepository appeals, AppealHistoryRepository history, AppealMessageRepository messages,
                             TransferRequestRepository transfers, DepartmentRepository departments,
                             RoutingRuleRepository rules, AppealFormPolicy formPolicy, DeadlineNormService normService,
                             ProductionCalendar calendar, ObjectMapper objectMapper, JdbcClient jdbc,
                             SignalDetector signals, PlatformTransactionManager transactionManager,
                             @Value("${appeals.timezone}") String timezone) {
        this.appeals = appeals;
        this.history = history;
        this.messages = messages;
        this.transfers = transfers;
        this.departments = departments;
        this.rules = rules;
        this.formPolicy = formPolicy;
        this.normService = normService;
        this.calendar = calendar;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.signals = signals;
        this.transactions = new TransactionTemplate(transactionManager);
        this.zone = ZoneId.of(timezone);
    }

    public boolean hasDemoData() {
        return jdbc.sql("select exists(select 1 from appeal where demo)").query(Boolean.class).single();
    }

    /** Удалить демонстрационные обращения (история, переписка и передачи уходят каскадом) и демо-сотрудников. */
    public int clear() {
        int removed = jdbc.sql("delete from appeal where demo").update();
        jdbc.sql("delete from staff_member where subject like 'demo-%'").update();
        return removed;
    }

    /** Создать count обращений за последние days дней; при том же seed — те же данные. */
    public int generate(int count, int days, long seed) {
        Random random = new Random(seed);
        Instant now = Instant.now();
        rememberDemoStaff(now);
        Map<String, List<String>> typesByProduct = new HashMap<>();
        PRODUCTS.keySet().forEach(product -> typesByProduct.put(product, typesOf(product)));

        List<Customer> customers = new ArrayList<>();
        int customerCount = Math.max(20, count / 5);
        for (int i = 0; i < customerCount; i++) {
            List<String> names = random.nextInt(2) == 0 ? DemoTexts.FEMALE : DemoTexts.MALE;
            customers.add(new Customer(UUID.nameUUIDFromBytes(("demo-client-" + seed + "-" + i).getBytes()),
                    pick(random, names), "client" + (i + 1) + "@example.ru"));
        }

        int created = 0;
        for (int i = 0; i < count; i++) {
            Instant createdAt = randomMoment(random, now, days);
            Customer customer = pick(random, customers);
            // сначала вид обращения — по долям, затем продукт, для которого этот вид допустим
            String typeKey = weighted(random, TYPE_WEIGHTS);
            Map<String, Integer> products = new LinkedHashMap<>();
            PRODUCT_WEIGHTS.forEach((code, weight) -> {
                if (typesByProduct.get(code).contains(typeKey)) {
                    products.put(code, weight);
                }
            });
            String product = weighted(random, products);
            ContractSnapshot contract = customer.contract(product, createdAt, random);
            UUID id = UUID.nameUUIDFromBytes(("demo-appeal-" + seed + "-" + i).getBytes());
            try {
                String number = transactions.execute(status ->
                        createAppeal(id, customer, contract, typeKey, createdAt, now, random));
                customer.numbers.add(number);
                created++;
            } catch (RuntimeException exception) {
                log.warn("Демо-обращение {} не создано: {}", typeKey, exception.getMessage());
            }
        }
        log.info("Создано демонстрационных обращений: {}", created);
        return created;
    }

    private String createAppeal(UUID id, Customer customer, ContractSnapshot contract, String typeKey,
                                Instant createdAt, Instant now, Random random) {
        String category = typeKey.substring(0, typeKey.indexOf('/'));
        String type = typeKey.substring(typeKey.indexOf('/') + 1);
        LocalDate registeredOn = LocalDate.ofInstant(createdAt, zone);

        Map<String, String> submitted = submittedDetails(category, type, contract, registeredOn, customer, random);
        Map<String, String> details = formPolicy.details(category, type, contract.getProductCode(), contract, submitted);
        DemoTexts.Story story = pick(random, DemoTexts.STORIES.get(typeKey));
        String subject = fill(story.subject(), details, contract);
        String description = fill(story.description(), details, contract);
        String number = "APP-" + NUMBER_DATE.format(createdAt) + "-" + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);

        Appeal appeal = new Appeal(id, number, customer.id, customer.name, customer.email, contract, category, type,
                json(details), subject, description, createdAt);
        appeal.markDemo();
        // примерно каждое пятое обращение звонят, каждое двадцатое приносят в офис
        double way = random.nextDouble();
        AppealChannel channel = way < 0.75 ? AppealChannel.CABINET : way < 0.95 ? AppealChannel.PHONE : AppealChannel.OFFICE;
        String operator = channel == AppealChannel.CABINET ? null : pick(random, DemoTexts.OPERATORS);
        if (operator != null) {
            appeal.registeredBy(channel, operator);
        }
        DeadlineNorm norm = normService.choose(category, type, contract.getProductCode(), details, registeredOn);
        LocalDate legalDeadline = calendar.deadline(registeredOn, norm.getTermDays(), norm.getCounting());
        appeal.applyNorm(norm, legalDeadline);
        // идентификатор задан заранее — save() сливает и возвращает управляемый экземпляр, дальше работаем с ним
        appeal = appeals.save(appeal);
        // сигналы в тексте клиента — как при обычной регистрации (решение 35)
        appeals.flush();
        signals.record(appeal.getId(), subject + "\n" + description, createdAt);
        note(appeal, "CREATED", operator == null
                ? "Клиент зарегистрировал обращение. Ожидается автоматическая маршрутизация."
                : "Оператор принял обращение со слов клиента " + channel.label() + ". Ожидается автоматическая маршрутизация.",
                operator == null ? customer.name : operator, createdAt);
        note(appeal, "DEADLINE_SET", "Срок ответа по закону — до " + legalDeadline.format(DAY) + ": "
                + norm.getTitle().toLowerCase(Locale.ROOT) + " (" + norm.getLegalBasis() + ")", "Подсистема", createdAt);

        Timeline time = new Timeline(createdAt, now);
        RoutingRule rule = rules.findByCategoryAndSubcategory(category, type)
                .or(() -> rules.findByCategoryAndSubcategory(category, "GENERAL"))
                .orElseThrow(() -> new IllegalStateException("нет правила для " + typeKey));
        if (!time.next(Duration.ofMinutes(1 + random.nextInt(20)))) {
            return number;
        }
        Department department = rule.getDepartment();
        appeal.routeTo(department, rule.getPriority(), time.at.plus(Duration.ofHours(rule.getSlaHours())), time.at);
        note(appeal, "ROUTED", "Обращение автоматически направлено в отдел «" + department.getName() + "». SLA: "
                + rule.getSlaHours() + " ч.", "Сервис маршрутизации", time.at);

        // передача в другой отдел — типичная ошибка маршрутизации
        Staff worker = specialist(department, random);
        if (!take(appeal, worker, time, random)) {
            return number;
        }
        List<String> transfer = TRANSFERS.get(department.getCode());
        if (transfer != null && random.nextDouble() < 0.06) {
            Department target = departments.findByCode(transfer.get(0)).orElseThrow();
            if (!transfer(appeal, department, target, worker, transfer.get(1), time, random)) {
                return number;
            }
            department = target;
            worker = specialist(target, random);
            if (!take(appeal, worker, time, random)) {
                return number;
            }
        }

        // запрос документов, ответ клиента, продление срока
        boolean documents = random.nextDouble() < (category.equals("CLAIM") || type.equals("PRETENSION") ? 0.35 : 0.12);
        boolean clientAnswered = true;
        if (documents) {
            if (!time.nextWorking(1 + random.nextInt(2), random)) {
                return number;
            }
            message(appeal, AuthorType.EMPLOYEE, worker.name, pick(random, DemoTexts.DOCUMENT_REQUESTS), time.at);
            changeStatus(appeal, AppealStatus.WAITING_CUSTOMER, worker, time.at);
            if (norm.getMaxExtensionDays() > 0 && random.nextDouble() < 0.35) {
                if (!time.next(Duration.ofHours(3 + random.nextInt(20)))) {
                    return number;
                }
                extend(appeal, department, norm, time, random);
            }
            clientAnswered = random.nextDouble() < 0.85;
            if (clientAnswered) {
                if (!time.next(Duration.ofHours(18 + random.nextInt(120)))) {
                    return number;
                }
                message(appeal, AuthorType.CLIENT, customer.name, pick(random, DemoTexts.CLIENT_REPLIES), time.at);
                appeal.changeStatus(AppealStatus.IN_PROGRESS, null, null, time.at);
                note(appeal, "STATUS_CHANGED", "Клиент ответил — обращение снова в работе", "Подсистема", time.at);
            }
        }

        // ответ по существу: обычно в срок, иногда с опозданием — для аналитики соблюдения сроков
        LocalDate deadline = appeal.getLegalDeadline();
        int span = Math.max(1, calendar.workingDaysAfter(registeredOn, deadline));
        double share = random.nextDouble();
        double part = share < 0.68 ? 0.15 + random.nextDouble() * 0.5
                : share < 0.92 ? 0.65 + random.nextDouble() * 0.33 : 1.05 + random.nextDouble() * 0.45;
        LocalDate resolveDay = calendar.addWorkingDays(registeredOn, Math.max(1, (int) Math.round(span * part)));
        Instant resolveAt = workingTime(resolveDay, random);
        if (!time.until(resolveAt.isAfter(time.at) ? resolveAt : time.at.plus(Duration.ofHours(2)))) {
            return number;
        }
        AppealOutcome outcome = clientAnswered ? outcome(category, type, contract.getProductCode(), random)
                : (random.nextBoolean() ? AppealOutcome.EXPLAINED : AppealOutcome.REJECTED);
        String answer = pick(random, DemoTexts.ANSWERS.get(outcome))
                .replace("{amount}", money(5_000 + random.nextInt(120) * 1_000));
        // клиент иногда выбирает не тот тип — сотрудник исправляет его при ответе (решение 23)
        String[] finalType = CORRECTIONS.containsKey(typeKey) && random.nextDouble() < 0.25
                ? CORRECTIONS.get(typeKey).split("/") : new String[]{category, type};
        appeal.resolve(outcome, finalType[0], finalType[1], time.at);
        message(appeal, AuthorType.EMPLOYEE, worker.name, answer, time.at);
        note(appeal, "RESOLVED", "Клиенту направлен ответ по существу. Итог: " + outcome.label().toLowerCase(Locale.ROOT),
                worker.name, time.at);

        if (random.nextDouble() < 0.75 && time.next(Duration.ofDays(5 + random.nextInt(8)))) {
            changeStatus(appeal, AppealStatus.CLOSED, worker, time.at);
        }
        return number;
    }

    // ---------- шаги процесса ----------

    private boolean take(Appeal appeal, Staff worker, Timeline time, Random random) {
        if (!time.next(Duration.ofHours(1 + random.nextInt(26)))) {
            return false;
        }
        time.at = workingMoment(time.at, random);
        if (time.at.isAfter(time.now)) {
            return false;
        }
        changeStatus(appeal, AppealStatus.IN_PROGRESS, worker, time.at);
        return true;
    }

    private boolean transfer(Appeal appeal, Department source, Department target, Staff worker, String reason,
                             Timeline time, Random random) {
        if (!time.next(Duration.ofHours(2 + random.nextInt(6)))) {
            return false;
        }
        TransferRequest request = transfers.save(new TransferRequest(UUID.randomUUID(), appeal, source, target,
                worker.subject, worker.name, reason, time.at));
        note(appeal, "TRANSFER_REQUESTED", "Запрошена передача из отдела «" + source.getName() + "» в отдел «"
                + target.getName() + "». Причина: " + reason, worker.name, time.at);
        if (!time.next(Duration.ofHours(1 + random.nextInt(5)))) {
            return false;
        }
        Staff reviewer = supervisor(target);
        int slaHours = rules.findFirstByDepartmentCodeOrderBySlaHoursAsc(target.getCode()).map(RoutingRule::getSlaHours).orElse(24);
        appeal.transferTo(target, time.at.plus(Duration.ofHours(slaHours)), time.at);
        request.approve(reviewer.subject, reviewer.name, "Принимаем", time.at);
        note(appeal, "TRANSFER_APPROVED", "Руководитель отдела «" + target.getName()
                + "» согласовал передачу. Комментарий: Принимаем", reviewer.name, time.at);
        return true;
    }

    private void extend(Appeal appeal, Department department, DeadlineNorm norm, Timeline time, Random random) {
        LocalDate today = LocalDate.ofInstant(time.at, zone);
        if (today.isAfter(appeal.getLegalDeadline())) {
            return;
        }
        int days = Math.min(norm.getMaxExtensionDays(), 5 + random.nextInt(6));
        LocalDate extended = calendar.addWorkingDays(appeal.getLegalDeadline(), days);
        String reason = "Запрошены дополнительные документы, без них ответить по существу нельзя";
        Staff supervisor = supervisor(department);
        appeal.extendLegalDeadline(days, extended, reason, today, time.at);
        message(appeal, AuthorType.EMPLOYEE, supervisor.name, "Срок рассмотрения вашего обращения продлён на " + days
                + " раб. дн. — до " + extended.format(DAY) + ".\nПричина: " + reason, time.at);
        note(appeal, "DEADLINE_EXTENDED", "Срок по закону продлён на " + days + " раб. дн., до " + extended.format(DAY)
                + ". Причина: " + reason, supervisor.name, time.at);
    }

    private void changeStatus(Appeal appeal, AppealStatus status, Staff worker, Instant at) {
        AppealStatus previous = appeal.getStatus();
        appeal.changeStatus(status, worker.subject, worker.name, at);
        note(appeal, "STATUS_CHANGED", "Статус изменён: «" + previous.label() + "» → «" + status.label() + "»", worker.name, at);
    }

    private void note(Appeal appeal, String action, String description, String actor, Instant at) {
        history.save(new AppealHistory(appeal, action, description, actor, at));
    }

    private void message(Appeal appeal, AuthorType author, String name, String body, Instant at) {
        messages.save(new AppealMessage(appeal, author, name, body, at));
        note(appeal, "MESSAGE_ADDED", "Добавлено сообщение от: " + name, name, at);
    }

    // ---------- содержание ----------

    private List<String> typesOf(String product) {
        List<String> result = new ArrayList<>();
        for (FormCategory category : formPolicy.schema(product).categories()) {
            for (FormAppealType type : category.types()) {
                result.add(category.code() + "/" + type.code());
            }
        }
        return result;
    }

    private Map<String, String> submittedDetails(String category, String type, ContractSnapshot contract,
                                                 LocalDate registeredOn, Customer customer, Random random) {
        Map<String, String> values = new HashMap<>();
        FormAppealType schemaType = formPolicy.schema(contract.getProductCode()).categories().stream()
                .filter(item -> item.code().equals(category))
                .flatMap(item -> item.types().stream())
                .filter(item -> item.code().equals(type))
                .findFirst().orElseThrow();
        for (FormField field : schemaType.fields()) {
            if (field.contractAttribute() != null) {
                continue;
            }
            String value = switch (field.code()) {
                case "incidentDate" -> {
                    // иногда — дата вне периода договора: проверка покрытия это покажет
                    LocalDate from = random.nextDouble() < 0.05 ? contract.getValidFrom().minusDays(20) : contract.getValidFrom();
                    long range = Math.max(1, Math.min(60, registeredOn.toEpochDay() - from.toEpochDay()));
                    yield registeredOn.minusDays(1 + random.nextInt((int) range)).toString();
                }
                case "incidentPlace" -> category.equals("CLAIM") && type.equals("AUTO")
                        ? pick(random, DemoTexts.PLACES) : pick(random, DemoTexts.ADDRESSES);
                case "violationDate" -> registeredOn.minusDays(random.nextDouble() < 0.15
                        ? 190 + random.nextInt(60) : 5 + random.nextInt(120)).toString();
                case "claimAmount" -> Integer.toString(20_000 + random.nextInt(760) * 500);
                case "desiredOutcome" -> pick(random, DemoTexts.DESIRED.getOrDefault(type, List.of("Прошу разобраться")));
                case "paymentDate" -> registeredOn.minusDays(1 + random.nextInt(40)).toString();
                case "paymentAmount" -> Integer.toString(3_000 + random.nextInt(620) * 100);
                case "relatedAppealNumber" -> customer.numbers.isEmpty()
                        ? "APP-" + NUMBER_DATE.format(registeredOn.minusDays(40).atStartOfDay(zone)) + "-" + "5A3C1E"
                        : customer.numbers.get(customer.numbers.size() - 1);
                case "systemSection" -> pick(random, DemoTexts.SECTIONS);
                case "device" -> pick(random, DemoTexts.DEVICES);
                case "errorText" -> pick(random, DemoTexts.ERRORS);
                default -> null;
            };
            if (value != null) {
                values.put(field.code(), value);
            }
        }
        return values;
    }

    private static String fill(String template, Map<String, String> details, ContractSnapshot contract) {
        String object = contract.getInsuredObject();
        String date = details.containsKey("incidentDate")
                ? LocalDate.parse(details.get("incidentDate")).format(DAY)
                : details.containsKey("paymentDate") ? LocalDate.parse(details.get("paymentDate")).format(DAY) : "на днях";
        String place = details.getOrDefault("incidentPlace", details.getOrDefault("objectAddress", object));
        String amount = details.containsKey("claimAmount") ? money(Integer.parseInt(details.get("claimAmount")))
                : details.containsKey("paymentAmount") ? money(Integer.parseInt(details.get("paymentAmount"))) : "";
        return template.replace("{object}", object.replaceFirst("^[^:]+: ", ""))
                .replace("{date}", date).replace("{place}", place).replace("{amount}", amount);
    }

    private static AppealOutcome outcome(String category, String type, String product, Random random) {
        double r = random.nextDouble();
        if (category.equals("CLAIM")) {
            // по ОСАГО больше споров о размере и форме возмещения
            boolean osago = product.equals("OSAGO");
            return r < (osago ? 0.40 : 0.55) ? AppealOutcome.SATISFIED
                    : r < (osago ? 0.70 : 0.80) ? AppealOutcome.PARTIALLY_SATISFIED
                    : r < 0.95 ? AppealOutcome.REJECTED : AppealOutcome.EXPLAINED;
        }
        if (type.equals("PRETENSION")) {
            return r < 0.25 ? AppealOutcome.SATISFIED : r < 0.55 ? AppealOutcome.PARTIALLY_SATISFIED : AppealOutcome.REJECTED;
        }
        if (category.equals("COMPLAINT")) {
            return r < 0.45 ? AppealOutcome.EXPLAINED : r < 0.80 ? AppealOutcome.SATISFIED : AppealOutcome.REJECTED;
        }
        if (category.equals("TECHNICAL") || category.equals("OTHER")) {
            return r < 0.65 ? AppealOutcome.EXPLAINED : AppealOutcome.SATISFIED;
        }
        return r < 0.70 ? AppealOutcome.SATISFIED : r < 0.95 ? AppealOutcome.EXPLAINED : AppealOutcome.REJECTED;
    }

    // ---------- время ----------

    /** День за последние days дней, ближе к сегодня — чаще; будни и рабочие часы — чаще. */
    private Instant randomMoment(Random random, Instant now, int days) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        for (int attempt = 0; ; attempt++) {
            LocalDate day = today.minusDays((long) (days * Math.pow(random.nextDouble(), 1.15)));
            if (!calendar.isWorkday(day) && random.nextDouble() < 0.7 && attempt < 5) {
                continue;
            }
            int minute = 8 * 60 + (int) (13 * 60 * random.nextDouble());
            Instant moment = day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant();
            if (moment.isBefore(now.minus(Duration.ofMinutes(10)))) {
                return moment;
            }
        }
    }

    private Instant workingTime(LocalDate day, Random random) {
        return day.atTime(LocalTime.of(10 + random.nextInt(8), random.nextInt(60))).atZone(zone).toInstant();
    }

    /** Ближайший рабочий момент: сотрудники работают в будни с 9 до 18. */
    private Instant workingMoment(Instant moment, Random random) {
        LocalDate day = LocalDate.ofInstant(moment, zone);
        LocalTime clock = LocalTime.ofInstant(moment, zone);
        if (!calendar.isWorkday(day) || clock.isAfter(LocalTime.of(18, 0))) {
            day = calendar.addWorkingDays(day, 1);
            return workingTime(day, random);
        }
        if (clock.isBefore(LocalTime.of(9, 0))) {
            return workingTime(day, random);
        }
        return moment;
    }

    /** Время процесса: шаг выполняется, только если к «сейчас» он уже наступил. */
    private final class Timeline {
        private Instant at;
        private final Instant now;

        Timeline(Instant start, Instant now) {
            this.at = start;
            this.now = now;
        }

        boolean next(Duration step) {
            return until(at.plus(step));
        }

        boolean nextWorking(int workingDays, Random random) {
            LocalDate day = calendar.addWorkingDays(LocalDate.ofInstant(at, zone), workingDays);
            return until(workingTime(day, random));
        }

        boolean until(Instant moment) {
            if (moment.isAfter(now)) {
                return false;
            }
            at = moment;
            return true;
        }
    }

    // ---------- справочные данные ----------

    private void rememberDemoStaff(Instant now) {
        STAFF.forEach((department, staff) -> staff.stream()
                .filter(member -> member.subject.startsWith("demo-"))
                .forEach(member -> jdbc.sql("""
                                insert into staff_member (subject, full_name, department_code, supervisor, last_seen_at)
                                values (:subject, :name, :department, :supervisor, :now)
                                on conflict (subject) do nothing""")
                        .param("subject", member.subject)
                        .param("name", member.name)
                        .param("department", department)
                        .param("supervisor", member.supervisor)
                        .param("now", Timestamp.from(now))
                        .update()));
    }

    private static Staff specialist(Department department, Random random) {
        List<Staff> specialists = STAFF.get(department.getCode()).stream().filter(member -> !member.supervisor).toList();
        return specialists.get(random.nextInt(specialists.size()));
    }

    private static Staff supervisor(Department department) {
        return STAFF.get(department.getCode()).stream().filter(Staff::supervisor).findFirst().orElseThrow();
    }

    private String json(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String money(int amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', ' ');
    }

    private static <T> T pick(Random random, List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /** Взвешенный выбор; ключи сортируются — порядок Map.of не задан, а результат должен повторяться. */
    private static String weighted(Random random, Map<String, Integer> weights) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int point = random.nextInt(total);
        for (Map.Entry<String, Integer> entry : new TreeMap<>(weights).entrySet()) {
            point -= entry.getValue();
            if (point < 0) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("пустые веса");
    }

    /** Вымышленный клиент: договор каждого вида заводится при первом обращении по нему. */
    private final class Customer {
        private final UUID id;
        private final String name;
        private final String email;
        private final Map<String, ContractSnapshot> contracts = new HashMap<>();
        private final List<String> numbers = new ArrayList<>();

        Customer(UUID id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        ContractSnapshot contract(String product, Instant firstAppeal, Random random) {
            LocalDate registeredOn = LocalDate.ofInstant(firstAppeal, zone);
            return contracts.computeIfAbsent(product, code -> {
                LocalDate from = registeredOn.minusDays(10 + random.nextInt(190));
                LocalDate to = from.plusYears(1).minusDays(1);
                String object = switch (code) {
                    case "CASCO", "OSAGO" -> "Автомобиль: " + pick(random, DemoTexts.CARS) + ", госномер " + plate(random);
                    case "LIFE" -> "Застрахованное лицо: " + name;
                    default -> "Квартира: " + pick(random, DemoTexts.ADDRESSES);
                };
                String policy = POLICY_PREFIX.get(code) + "-" + from.getYear() + "-" + String.format("%06d", random.nextInt(1_000_000));
                return new ContractSnapshot(UUID.nameUUIDFromBytes(("demo-contract-" + id + "-" + code).getBytes()),
                        policy, code, PRODUCTS.get(code), object, from, to, null,
                        to.isBefore(registeredOn) ? "EXPIRED" : "ACTIVE");
            });
        }

        private String plate(Random random) {
            String letters = "АВЕКМНОРСТУХ";
            return "" + letters.charAt(random.nextInt(letters.length())) + (100 + random.nextInt(900))
                    + letters.charAt(random.nextInt(letters.length())) + letters.charAt(random.nextInt(letters.length()))
                    + List.of("77", "97", "99", "177", "777", "50", "750").get(random.nextInt(7));
        }
    }
}
