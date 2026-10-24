package ru.mtuci.appeals.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.NewNormVersionRequest;
import ru.mtuci.appeals.api.ApiModels.NormVersionResponse;
import ru.mtuci.appeals.api.ApiModels.RoutingRuleResponse;
import ru.mtuci.appeals.api.ApiModels.RoutingSettingsResponse;
import ru.mtuci.appeals.api.ApiModels.SaveRoutingRuleRequest;
import ru.mtuci.appeals.api.ApiModels.SettingsAuditResponse;
import ru.mtuci.appeals.api.ApiModels.UncoveredTypeResponse;
import ru.mtuci.appeals.security.CurrentUser;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Настройки подсистемы, которые меняет администратор без программиста (решение 34):
 * правила маршрутизации и версии нормативов сроков. Каждое изменение — с причиной и в журнал.
 *
 * Главное ограничение — закон: срок отдела не может быть длиннее срока ответа по закону
 * для этого вида обращения. Подсистема оставляет ещё запас на согласование и отправку
 * ответа и сравнивает консервативно: часы отдела — с календарными днями норматива, даже
 * если норматив в рабочих днях.
 */
@Service
public class SettingsService {

    /** Дней на согласование ответа и его отправку после срока отдела. */
    static final int RESERVE_DAYS = 2;
    private static final String GENERAL = "GENERAL";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Map<String, String> COUNTING = Map.of("WORKING_DAYS", "раб. дн.",
            "CALENDAR_DAYS", "календ. дн.", "CALENDAR_EXCEPT_HOLIDAYS", "календ. дн. без праздников");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;

    public SettingsService(JdbcClient jdbc, ObjectMapper objectMapper, @Value("${appeals.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.zone = ZoneId.of(timezone);
    }

    // ---------- маршрутизация ----------

    @Transactional(readOnly = true)
    public RoutingSettingsResponse routing() {
        LocalDate today = LocalDate.now(zone);
        List<RoutingRuleResponse> rules = jdbc.sql("""
                        select r.category, c.label as category_label, r.subcategory, t.label as type_label,
                               d.code as department_code, d.name as department_name, r.priority, r.sla_hours
                        from routing_rule r
                        join appeal_category c on c.code = r.category
                        join department d on d.id = r.department_id
                        left join appeal_type t on t.category = r.category and t.code = r.subcategory
                        order by c.sort_order, case when r.subcategory = 'GENERAL' then 0 else 1 end, t.sort_order""")
                .query((rs, row) -> {
                    LegalLimit limit = limitFor(rs.getString("category"), rs.getString("subcategory"), today);
                    return new RoutingRuleResponse(rs.getString("category"), rs.getString("category_label"),
                            rs.getString("subcategory"), rs.getString("type_label"), rs.getString("department_code"),
                            rs.getString("department_name"), rs.getString("priority"), rs.getInt("sla_hours"),
                            limit.maxSlaHours(), limit.explanation());
                })
                .list();
        List<UncoveredTypeResponse> uncovered = jdbc.sql("""
                        select t.category, c.label as category_label, t.code as type, t.label as type_label
                        from appeal_type t join appeal_category c on c.code = t.category
                        where not exists (select 1 from routing_rule r where r.category = t.category
                                          and (r.subcategory = t.code or r.subcategory = 'GENERAL'))
                        order by c.sort_order, t.sort_order""")
                .query(UncoveredTypeResponse.class)
                .list();
        return new RoutingSettingsResponse(rules, uncovered);
    }

    @Transactional
    public void saveRule(CurrentUser user, SaveRoutingRuleRequest request) {
        String category = request.category();
        String subcategory = request.subcategory();
        requireType(category, subcategory);
        Long departmentId = jdbc.sql("select id from department where code = :code")
                .param("code", request.departmentCode()).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный отдел: " + request.departmentCode()));
        LegalLimit limit = limitFor(category, subcategory, LocalDate.now(zone));
        if (request.slaHours() > limit.maxSlaHours()) {
            throw new IllegalArgumentException("Срок отдела " + request.slaHours() + " ч длиннее допустимого: "
                    + limit.explanation());
        }

        Optional<Map<String, Object>> before = rule(category, subcategory);
        if (before.isPresent()) {
            jdbc.sql("""
                            update routing_rule set department_id = :department, priority = :priority, sla_hours = :sla
                            where category = :category and subcategory = :subcategory""")
                    .param("department", departmentId).param("priority", request.priority().name())
                    .param("sla", request.slaHours()).param("category", category).param("subcategory", subcategory)
                    .update();
        } else {
            jdbc.sql("""
                            insert into routing_rule (category, subcategory, department_id, priority, sla_hours)
                            values (:category, :subcategory, :department, :priority, :sla)""")
                    .param("category", category).param("subcategory", subcategory).param("department", departmentId)
                    .param("priority", request.priority().name()).param("sla", request.slaHours())
                    .update();
        }
        audit(user, "ROUTING_RULE", category + "/" + subcategory, before.isPresent() ? "UPDATED" : "CREATED",
                before.orElse(null), rule(category, subcategory).orElseThrow(), request.reason());
    }

    /** Без правила обращения этого вида уйдут на ручную классификацию (решение 31). */
    @Transactional
    public void deleteRule(CurrentUser user, String category, String subcategory, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Укажите причину изменения");
        }
        Map<String, Object> before = rule(category, subcategory)
                .orElseThrow(() -> new IllegalArgumentException("Правила " + category + "/" + subcategory + " нет"));
        jdbc.sql("delete from routing_rule where category = :category and subcategory = :subcategory")
                .param("category", category).param("subcategory", subcategory).update();
        audit(user, "ROUTING_RULE", category + "/" + subcategory, "DELETED", before, null, reason.trim());
    }

    /**
     * Сколько часов может быть у отдела: самый короткий срок по закону среди нормативов,
     * которые могут достаться обращению этого вида, минус запас на согласование и отправку.
     */
    LegalLimit limitFor(String category, String subcategory, LocalDate today) {
        List<String> types = GENERAL.equals(subcategory)
                ? jdbc.sql("select code from appeal_type where category = :category").param("category", category)
                        .query(String.class).list()
                : List.of(subcategory);
        List<String> codes = new ArrayList<>();
        for (String type : types) {
            List<String> mapped = jdbc.sql("select norm_code from appeal_type_norm where category = :category and type_code = :type")
                    .param("category", category).param("type", type).query(String.class).list();
            codes.addAll(mapped.isEmpty() ? List.of(GENERAL) : mapped);
        }
        if (codes.isEmpty()) {
            codes.add(GENERAL);
        }
        record Norm(String title, int termDays, String counting, String legalBasis) {
        }
        Norm shortest = jdbc.sql("""
                        select title, term_days, counting, legal_basis from deadline_norm
                        where code in (:codes) and valid_from <= :today and (valid_to is null or valid_to >= :today)""")
                .param("codes", codes.stream().distinct().toList()).param("today", Date.valueOf(today))
                .query(Norm.class).list().stream()
                .min(Comparator.comparingInt(Norm::termDays))
                .orElseThrow(() -> new IllegalStateException("Нет действующего норматива для " + category + "/" + subcategory));
        int max = (shortest.termDays() - RESERVE_DAYS) * 24;
        return new LegalLimit(max, "не больше " + max + " ч: по закону «" + shortest.title().toLowerCase() + "» даёт "
                + shortest.termDays() + " " + COUNTING.get(shortest.counting()) + " (" + shortest.legalBasis()
                + "), " + RESERVE_DAYS + " дня остаются на согласование и отправку ответа");
    }

    record LegalLimit(int maxSlaHours, String explanation) {
    }

    // ---------- нормативы ----------

    @Transactional(readOnly = true)
    public List<NormVersionResponse> norms() {
        return jdbc.sql("""
                        select id, code, title, term_days, counting, max_extension_days, condition, legal_basis,
                               valid_from, valid_to
                        from deadline_norm order by code, valid_from""")
                .query(NormVersionResponse.class)
                .list();
    }

    /**
     * Новая версия норматива — когда меняется закон. Только с будущей даты: обращения,
     * зарегистрированные раньше, сохраняют свой срок (решение 20). Прежняя версия
     * действует до дня, предшествующего новой.
     */
    @Transactional
    public void addNormVersion(CurrentUser user, NewNormVersionRequest request) {
        LocalDate validFrom = request.validFrom();
        if (!validFrom.isAfter(LocalDate.now(zone))) {
            throw new IllegalArgumentException("Новая версия норматива действует только с будущей даты: "
                    + "обращения, зарегистрированные раньше, сохраняют свой срок");
        }
        record Version(long id, String title, String condition, LocalDate validFrom, LocalDate validTo) {
        }
        List<Version> versions = jdbc.sql("""
                        select id, title, condition, valid_from, valid_to from deadline_norm
                        where code = :code order by valid_from""")
                .param("code", request.code()).query(Version.class).list();
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("Неизвестный норматив: " + request.code());
        }
        Version last = versions.get(versions.size() - 1);
        if (!validFrom.isAfter(last.validFrom())) {
            throw new IllegalArgumentException("Уже есть версия с " + last.validFrom().format(DAY) + ", новая должна начинаться позже");
        }
        jdbc.sql("update deadline_norm set valid_to = :to where id = :id")
                .param("to", Date.valueOf(validFrom.minusDays(1))).param("id", last.id()).update();
        jdbc.sql("""
                        insert into deadline_norm (code, title, term_days, counting, max_extension_days, condition,
                                                   legal_basis, valid_from)
                        values (:code, :title, :term, :counting, :extension, :condition, :basis, :from)""")
                .param("code", request.code()).param("title", last.title()).param("term", request.termDays())
                .param("counting", request.counting().name()).param("extension", request.maxExtensionDays())
                .param("condition", last.condition()).param("basis", request.legalBasis().trim())
                .param("from", Date.valueOf(validFrom))
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("termDays", request.termDays());
        after.put("counting", request.counting().name());
        after.put("maxExtensionDays", request.maxExtensionDays());
        after.put("legalBasis", request.legalBasis().trim());
        after.put("validFrom", validFrom.toString());
        audit(user, "DEADLINE_NORM", request.code() + " с " + validFrom.format(DAY), "CREATED", null, after, request.reason());
    }

    // ---------- журнал ----------

    @Transactional(readOnly = true)
    public List<SettingsAuditResponse> auditLog() {
        return jdbc.sql("""
                        select id, changed_at, actor_name, entity, entity_key, action,
                               before_value::text as before_value, after_value::text as after_value, reason
                        from settings_audit order by changed_at desc, id desc limit 100""")
                .query((rs, row) -> new SettingsAuditResponse(rs.getLong("id"), rs.getTimestamp("changed_at").toInstant(),
                        rs.getString("actor_name"), rs.getString("entity"), rs.getString("entity_key"),
                        rs.getString("action"), rs.getString("before_value"), rs.getString("after_value"),
                        rs.getString("reason")))
                .list();
    }

    // ---------- сроки хранения (решение 41) ----------

    @Transactional
    public void setRetentionYears(CurrentUser user, int years, String reason) {
        int before = jdbc.sql("select years from data_retention where id = 1").query(Integer.class).single();
        if (before == years) {
            return;
        }
        jdbc.sql("update data_retention set years = :years where id = 1").param("years", years).update();
        audit(user, "RETENTION", "Срок хранения персональных данных", "UPDATED", Map.of("years", before),
                Map.of("years", years), reason);
    }

    // ---------- помощник сотрудника (решение 35) ----------

    /** RULES — правила и поиск, LOCAL_MODEL — локальная модель в контуре страховой. */
    public String assistantEngine() {
        return jdbc.sql("select engine from assistant_setting where id = 1").query(String.class).single();
    }

    @Transactional
    public void setAssistantEngine(CurrentUser user, String engine, String reason) {
        String before = assistantEngine();
        if (before.equals(engine)) {
            return;
        }
        jdbc.sql("update assistant_setting set engine = :engine where id = 1").param("engine", engine).update();
        audit(user, "ASSISTANT", "Помощник сотрудника", "UPDATED", Map.of("engine", before), Map.of("engine", engine),
                reason);
    }

    private void audit(CurrentUser user, String entity, String key, String action,
                       Map<String, Object> before, Map<String, Object> after, String reason) {
        jdbc.sql("""
                        insert into settings_audit (changed_at, actor_subject, actor_name, entity, entity_key, action,
                                                    before_value, after_value, reason)
                        values (:at, :subject, :name, :entity, :key, :action,
                                cast(:before as jsonb), cast(:after as jsonb), :reason)""")
                .param("at", Timestamp.from(Instant.now())).param("subject", user.subject())
                .param("name", Objects.requireNonNullElse(user.name(), user.subject()))
                .param("entity", entity).param("key", key).param("action", action)
                .param("before", json(before)).param("after", json(after)).param("reason", reason.trim())
                .update();
    }

    private Optional<Map<String, Object>> rule(String category, String subcategory) {
        return jdbc.sql("""
                        select d.code as department, r.priority, r.sla_hours from routing_rule r
                        join department d on d.id = r.department_id
                        where r.category = :category and r.subcategory = :subcategory""")
                .param("category", category).param("subcategory", subcategory)
                .query((rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("department", rs.getString("department"));
                    value.put("priority", rs.getString("priority"));
                    value.put("slaHours", rs.getInt("sla_hours"));
                    return value;
                })
                .optional();
    }

    private void requireType(String category, String subcategory) {
        boolean categoryExists = jdbc.sql("select exists(select 1 from appeal_category where code = :code)")
                .param("code", category).query(Boolean.class).single();
        if (!categoryExists) {
            throw new IllegalArgumentException("Неизвестная категория: " + category);
        }
        if (!GENERAL.equals(subcategory)) {
            boolean typeExists = jdbc.sql("select exists(select 1 from appeal_type where category = :category and code = :code)")
                    .param("category", category).param("code", subcategory).query(Boolean.class).single();
            if (!typeExists) {
                throw new IllegalArgumentException("Неизвестный тип обращения: " + category + "/" + subcategory);
            }
        }
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
