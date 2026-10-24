package ru.mtuci.appeals.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.AssistantAdviceResponse;
import ru.mtuci.appeals.api.ApiModels.AssistantSettingsResponse;
import ru.mtuci.appeals.api.ApiModels.DraftResponse;
import ru.mtuci.appeals.api.ApiModels.SimilarAppealResponse;
import ru.mtuci.appeals.api.ApiModels.TypeHintResponse;
import ru.mtuci.appeals.assistant.AssistantEngine.AppealText;
import ru.mtuci.appeals.assistant.AssistantEngine.DraftInput;
import ru.mtuci.appeals.assistant.AssistantEngine.TypeGuess;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.security.CurrentUser;
import ru.mtuci.appeals.service.AppealAccess;
import ru.mtuci.appeals.service.AppealFormPolicy;
import ru.mtuci.appeals.service.AppealNotFoundException;
import ru.mtuci.appeals.service.DeadlineNormService;
import ru.mtuci.appeals.service.ProductionCalendar;
import ru.mtuci.appeals.service.SettingsService;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Помощник сотрудника (решение 35): что заметить в тексте клиента, не претензия ли это,
 * как решали похожие обращения и черновик ответа. Работает на правилах и поиске, по выбору
 * администратора — на локальной модели; модель недоступна — отвечают правила, как кабинет
 * работает без учётной системы. Каждая показанная подсказка учитывается вместе с решением
 * сотрудника: по доле принятых видно, приносит ли помощник пользу.
 *
 * Обращения к модели — вне транзакции базы: модель может думать десятки секунд.
 */
@Service
public class AssistantService {

    public static final String TYPE_HINT = "TYPE_HINT";
    public static final String SIMILAR = "SIMILAR";
    public static final String DRAFT = "DRAFT";
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String DEGRADED = "Локальная модель недоступна — подсказки дают правила";
    private static final int SIMILAR_LIMIT = 4;
    private static final int SIMILAR_FOR_DRAFT = 2;
    private static final String VOWEL_ENDINGS = "аяеёоуыиюйьАЯЕЁОУЫИЮЙЬ";

    private final AppealRepository appeals;
    private final AppealAccess access;
    private final SignalDetector signals;
    private final RulesEngine rules;
    private final LocalModelEngine model;
    private final SettingsService settings;
    private final DeadlineNormService normService;
    private final ProductionCalendar calendar;
    private final AppealFormPolicy formPolicy;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnly;
    private final ZoneId zone;

    public AssistantService(AppealRepository appeals, AppealAccess access, SignalDetector signals, RulesEngine rules,
                            LocalModelEngine model, SettingsService settings, DeadlineNormService normService,
                            ProductionCalendar calendar, AppealFormPolicy formPolicy, JdbcClient jdbc,
                            ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                            @Value("${appeals.timezone}") String timezone) {
        this.appeals = appeals;
        this.access = access;
        this.signals = signals;
        this.rules = rules;
        this.model = model;
        this.settings = settings;
        this.normService = normService;
        this.calendar = calendar;
        this.formPolicy = formPolicy;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.zone = ZoneId.of(timezone);
    }

    /** Подсказки по обращению — сотруднику его отдела. */
    public AssistantAdviceResponse advice(CurrentUser user, UUID id) {
        Snapshot appeal = snapshot(user, id);
        AssistantEngine engine = engine();
        Result<Optional<TypeGuess>> guess = run(engine, chosen -> chosen.guessType(appeal.text()));
        TypeHintResponse hint = guess.value().map(found -> typeHint(appeal, found, guess.source())).orElse(null);
        List<SimilarAppealResponse> similar = similar(appeal, appeal.text().category(), appeal.text().subcategory(), SIMILAR_LIMIT);
        if (hint != null) {
            shown(id, TYPE_HINT, guess.source(), user);
        }
        if (!similar.isEmpty()) {
            // похожие ищет полнотекстовый поиск базы — это всегда правила
            shown(id, SIMILAR, AssistantEngine.RULES, user);
        }
        return new AssistantAdviceResponse(engine.source(), guess.degraded() ? DEGRADED : null, signals.of(id),
                hint, similar, decisions(id));
    }

    /** Черновик ответа по итогу и итоговому типу, которые сотрудник выбрал в форме ответа. */
    public DraftResponse draft(CurrentUser user, UUID id, AppealOutcome outcome, String type) {
        Snapshot appeal = snapshot(user, id);
        String category = appeal.text().category();
        String subcategory = appeal.text().subcategory();
        if (type != null && !type.isBlank()) {
            String[] parts = type.split("/", 2);
            if (parts.length != 2 || !formPolicy.typeExists(parts[0], parts[1])) {
                throw new IllegalArgumentException("Неизвестный тип обращения: " + type);
            }
            category = parts[0];
            subcategory = parts[1];
        }
        Template template = template(category, subcategory, outcome);
        List<String> similarAnswers = similar(appeal, category, subcategory, SIMILAR_FOR_DRAFT).stream()
                .map(SimilarAppealResponse::answer).filter(Objects::nonNull).toList();
        DraftInput input = new DraftInput(appeal.text(), outcome, fill(template.body(), appeal), similarAnswers);
        Result<String> result = run(engine(), chosen -> chosen.draft(input));
        shown(id, DRAFT, result.source(), user);
        return new DraftResponse(result.value(), template.title(), result.source(), result.degraded() ? DEGRADED : null);
    }

    /** Сотрудник принял подсказку или отклонил её. */
    public void feedback(CurrentUser user, UUID id, String kind, String decision) {
        snapshot(user, id);
        int updated = jdbc.sql("""
                        update assistant_suggestion
                        set decision = :decision, decided_at = :at, decided_by = :subject
                        where appeal_id = :appeal and kind = :kind""")
                .param("decision", decision).param("at", Timestamp.from(Instant.now()))
                .param("subject", user.subject()).param("appeal", id).param("kind", kind)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("Эта подсказка по обращению не показывалась");
        }
    }

    /** Кто сейчас подсказывает и в каком состоянии модель — для администратора. */
    public AssistantSettingsResponse status() {
        var circuit = model.circuitBreaker();
        return new AssistantSettingsResponse(settings.assistantEngine(), model.modelName(), model.baseUrl(),
                circuit.getState().name(), circuit.getMetrics().getFailureRate());
    }

    // ---------- движок ----------

    private AssistantEngine engine() {
        return AssistantEngine.LOCAL_MODEL.equals(settings.assistantEngine()) ? model : rules;
    }

    /** Модель недоступна или ответила не по формату — тот же вопрос задаётся правилам. */
    private <T> Result<T> run(AssistantEngine engine, Function<AssistantEngine, T> call) {
        if (engine != rules) {
            try {
                return new Result<>(call.apply(engine), engine.source(), false);
            } catch (AssistantUnavailableException exception) {
                log.warn("Помощник: {} — подсказку дают правила", exception.getMessage());
                return new Result<>(call.apply(rules), AssistantEngine.RULES, true);
            }
        }
        return new Result<>(call.apply(rules), AssistantEngine.RULES, false);
    }

    private record Result<T>(T value, String source, boolean degraded) {
    }

    // ---------- обращение ----------

    private Snapshot snapshot(CurrentUser user, UUID id) {
        return readOnly.execute(status -> {
            Appeal appeal = appeals.findById(id).orElseThrow(() -> new AppealNotFoundException(id));
            access.checkWork(user, appeal);
            List<String> clientMessages = jdbc.sql("""
                            select body from appeal_message
                            where appeal_id = :appeal and author_type = 'CLIENT' and body <> ''
                            order by created_at""")
                    .param("appeal", id).query(String.class).list();
            ContractSnapshot contract = appeal.getContract();
            String productCode = contract != null && contract.getProductCode() != null
                    ? contract.getProductCode() : appeal.getRequestedProductCode();
            String productLabel = contract != null && contract.getProductName() != null
                    ? contract.getProductName() : productCode == null ? null : formPolicy.productLabel(productCode);
            AppealText text = new AppealText(appeal.getCategory(), appeal.getSubcategory(),
                    typeLabel(appeal.getCategory(), appeal.getSubcategory()), appeal.getSubject(),
                    appeal.getDescription(), clientMessages);
            return new Snapshot(id, appeal.getPublicNumber(), appeal.getCustomerName(), text, productCode, productLabel,
                    contract == null ? null : contract.getPolicyNumber(),
                    contract == null ? null : contract.getInsuredObject(),
                    readDetails(appeal.getDetailsJson()), appeal.getCreatedAt(), appeal.getLegalDeadline());
        });
    }

    private record Snapshot(UUID id, String publicNumber, String customerName, AppealText text, String productCode,
                            String productLabel, String policyNumber, String insuredObject, Map<String, String> details,
                            Instant createdAt, LocalDate legalDeadline) {
    }

    // ---------- подсказки ----------

    /** Какой срок по закону был бы у обращения как у претензии — от типа зависят срок и продление. */
    private TypeHintResponse typeHint(Snapshot appeal, TypeGuess guess, String source) {
        LocalDate registeredOn = LocalDate.ofInstant(appeal.createdAt(), zone);
        DeadlineNorm norm = normService.choose(guess.category(), guess.subcategory(), appeal.productCode(),
                appeal.details(), registeredOn);
        LocalDate deadline = calendar.deadline(registeredOn, norm.getTermDays(), norm.getCounting());
        return new TypeHintResponse(guess.category(), guess.subcategory(),
                typeLabel(guess.category(), guess.subcategory()), guess.reasons(), deadline, norm.getTitle(),
                norm.getLegalBasis(), norm.getMaxExtensionDays() > 0, appeal.legalDeadline(), source);
    }

    /**
     * Решённые обращения того же итогового типа: сначала похожие по словам (полнотекстовый
     * поиск PostgreSQL по-русски), затем свежие. Имена других клиентов в ответах скрыты.
     */
    private List<SimilarAppealResponse> similar(Snapshot appeal, String category, String subcategory, int limit) {
        return jdbc.sql("""
                        with q as (
                            select string_agg(quote_literal(lexeme), ' | ')::tsquery as query
                            from unnest(to_tsvector('russian', :text))
                        )
                        select a.public_number, a.subject, a.outcome, a.customer_name, a.created_at, a.resolved_at,
                               coalesce(ts_rank(to_tsvector('russian', a.subject || ' ' || a.description), q.query), 0) as rank,
                               (select m.body from appeal_message m
                                where m.appeal_id = a.id and m.author_type = 'EMPLOYEE' and m.created_at <= a.resolved_at
                                order by m.created_at desc limit 1) as answer
                        from appeal a cross join q
                        where a.id <> :id and a.outcome is not null and a.resolved_at is not null
                          and coalesce(a.final_category, a.category) = :category
                          and coalesce(a.final_subcategory, a.subcategory) = :subcategory
                        order by rank desc, a.resolved_at desc
                        limit :limit""")
                .param("text", appeal.text().subject() + " " + appeal.text().description())
                .param("id", appeal.id()).param("category", category).param("subcategory", subcategory)
                .param("limit", limit)
                .query((rs, row) -> {
                    Instant created = rs.getTimestamp("created_at").toInstant();
                    Instant resolved = rs.getTimestamp("resolved_at").toInstant();
                    LocalDate resolvedOn = LocalDate.ofInstant(resolved, zone);
                    String answer = rs.getString("answer");
                    return new SimilarAppealResponse(rs.getString("public_number"), rs.getString("subject"),
                            AppealOutcome.valueOf(rs.getString("outcome")), resolvedOn,
                            ChronoUnit.DAYS.between(LocalDate.ofInstant(created, zone), resolvedOn),
                            answer == null ? null : mask(answer, rs.getString("customer_name")));
                })
                .list();
    }

    /** Имя другого клиента в чужом ответе сотруднику не нужно: «Анна», «Анне», «Анной» → «[клиент]». */
    static String mask(String text, String customerName) {
        String result = text;
        for (String word : customerName == null ? new String[0] : customerName.split("\\s+")) {
            if (word.length() < 3) {
                continue;
            }
            String stem = word.length() > 3 && VOWEL_ENDINGS.indexOf(word.charAt(word.length() - 1)) >= 0
                    ? word.substring(0, word.length() - 1) : word;
            result = Pattern.compile("(?<!\\p{L})" + Pattern.quote(stem) + "\\p{L}{0,3}(?!\\p{L})")
                    .matcher(result).replaceAll("[клиент]");
        }
        return result;
    }

    /** Самый точный шаблон: для типа, для категории, общий для итога. */
    private Template template(String category, String subcategory, AppealOutcome outcome) {
        return jdbc.sql("""
                        select title, body from answer_template
                        where outcome = :outcome
                          and (category is null or category = :category)
                          and (subcategory is null or subcategory = :subcategory)
                        order by (subcategory is not null) desc, (category is not null) desc
                        limit 1""")
                .param("outcome", outcome.name()).param("category", category).param("subcategory", subcategory)
                .query((rs, row) -> new Template(rs.getString("title"), rs.getString("body")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Нет шаблона ответа для итога " + outcome));
    }

    private record Template(String title, String body) {
    }

    /** {…} — данные обращения и договора; чего нет, остаётся в […] — допишет сотрудник. */
    private String fill(String body, Snapshot appeal) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{клиент}", appeal.customerName());
        values.put("{номер}", appeal.publicNumber());
        values.put("{дата}", LocalDate.ofInstant(appeal.createdAt(), zone).format(DAY));
        values.put("{договор}", appeal.policyNumber() == null ? "[номер договора]" : appeal.policyNumber());
        values.put("{вид}", appeal.productLabel() == null ? "[вид страхования]" : appeal.productLabel());
        values.put("{объект}", appeal.insuredObject() == null ? "[объект страхования]" : appeal.insuredObject());
        values.put("{срок}", appeal.legalDeadline() == null ? "[срок]" : appeal.legalDeadline().format(DAY));
        String result = body;
        for (Map.Entry<String, String> value : values.entrySet()) {
            result = result.replace(value.getKey(), value.getValue());
        }
        return result;
    }

    // ---------- учёт ----------

    /** Подсказка показана; повторный показ обновляет источник, пока сотрудник не решил. */
    private void shown(UUID appealId, String kind, String source, CurrentUser user) {
        jdbc.sql("""
                        insert into assistant_suggestion (appeal_id, kind, source, shown_at, shown_to)
                        values (:appeal, :kind, :source, :at, :subject)
                        on conflict (appeal_id, kind) do update
                            set source = excluded.source, shown_at = excluded.shown_at, shown_to = excluded.shown_to
                            where assistant_suggestion.decision is null""")
                .param("appeal", appealId).param("kind", kind).param("source", source)
                .param("at", Timestamp.from(Instant.now())).param("subject", user.subject())
                .update();
    }

    private Map<String, String> decisions(UUID appealId) {
        Map<String, String> decisions = new HashMap<>();
        jdbc.sql("select kind, decision from assistant_suggestion where appeal_id = :appeal and decision is not null")
                .param("appeal", appealId)
                .query(rs -> {
                    decisions.put(rs.getString("kind"), rs.getString("decision"));
                });
        return decisions;
    }

    private String typeLabel(String category, String type) {
        return jdbc.sql("""
                        select c.label || ' · ' || coalesce(t.label, :type) from appeal_category c
                        left join appeal_type t on t.category = c.code and t.code = :type
                        where c.code = :category""")
                .param("category", category).param("type", type)
                .query(String.class).optional().orElse(category + " · " + type);
    }

    private Map<String, String> readDetails(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }
}
