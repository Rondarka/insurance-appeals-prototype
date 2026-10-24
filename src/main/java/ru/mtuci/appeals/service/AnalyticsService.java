package ru.mtuci.appeals.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.AnalyticsResponse;
import ru.mtuci.appeals.api.ApiModels.AnalyticsSummary;
import ru.mtuci.appeals.api.ApiModels.AssistantUsage;
import ru.mtuci.appeals.api.ApiModels.DepartmentOnTime;
import ru.mtuci.appeals.api.ApiModels.Rate;
import ru.mtuci.appeals.api.ApiModels.ShareItem;
import ru.mtuci.appeals.api.ApiModels.TransferFlow;
import ru.mtuci.appeals.api.ApiModels.TypeCorrection;
import ru.mtuci.appeals.api.ApiModels.WeekSeries;
import ru.mtuci.appeals.domain.AppealChannel;
import ru.mtuci.appeals.domain.AppealOutcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Обращения как источник сведений о продуктах и процессах (решение 23). Все показатели
 * строятся по представлению appeal_facts — одна строка на обращение — и по итоговому типу,
 * который сотрудник подтверждает при ответе. Каждый показатель поддерживает конкретное
 * решение руководителя; какое — подсказано в интерфейсе рядом с ним.
 *
 * Период выравнивается на понедельник, чтобы первая неделя в динамике была полной.
 */
@Service
public class AnalyticsService {

    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 730;
    private static final String PRETENSION = "f.final_category = 'COMPLAINT' and f.final_subcategory = 'PRETENSION'";
    private static final String NO_PRODUCT = "Вид страхования не указан";

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public AnalyticsService(JdbcClient jdbc, @Value("${appeals.timezone}") String timezone) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse analytics(int days, String product) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException("Период аналитики: от " + MIN_DAYS + " до " + MAX_DAYS + " дней");
        }
        String selected = product == null || product.isBlank() ? null : product;
        if (selected != null && !productExists(selected)) {
            throw new IllegalArgumentException("Неизвестный вид страхования: " + selected);
        }
        LocalDate to = LocalDate.now(zone);
        LocalDate from = to.minusDays(days - 1L).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Filter all = new Filter(from, null);
        Filter filter = new Filter(from, selected);

        List<LocalDate> weeks = new ArrayList<>();
        for (LocalDate week = from; !week.isAfter(to); week = week.plusWeeks(1)) {
            weeks.add(week);
        }
        return new AnalyticsResponse(from, to, selected, summary(filter), products(all), types(filter),
                weeks, weekly(filter, weeks), departments(filter), transfers(filter), corrections(filter),
                outcomes(filter), assistant(filter), channels(filter));
    }

    private AnalyticsSummary summary(Filter filter) {
        return jdbc.sql("""
                        select count(*) as received,
                               count(*) filter (where f.demo) as demo,
                               count(*) filter (where f.resolved_on is not null) as resolved,
                               count(*) filter (where f.resolved_on_time) as on_time,
                               avg(f.resolution_days) as avg_days,
                               count(*) filter (where f.overdue_open) as overdue_open,
                               count(*) filter (where f.outcome is not null) as with_outcome,
                               count(*) filter (where f.outcome in ('SATISFIED', 'PARTIALLY_SATISFIED')) as satisfied,
                               count(*) filter (where f.transferred) as transferred,
                               count(*) filter (where f.repeated) as repeated,
                               count(*) filter (where f.manually_classified) as manual,
                               count(*) filter (where f.accepted_without_contract) as without_contract,
                               count(*) filter (where f.type_corrected) as corrected,
                               count(*) filter (where %1$s) as pretensions,
                               count(*) filter (where %1$s and f.resolved_on is not null) as pretensions_resolved,
                               count(*) filter (where %1$s and f.resolved_on_time) as pretensions_on_time,
                               count(*) filter (where %1$s and f.overdue_open) as pretensions_overdue
                        from appeal_facts f""".formatted(PRETENSION) + filter.where())
                .params(filter.params())
                .query((rs, row) -> {
                    long received = rs.getLong("received");
                    long resolved = rs.getLong("resolved");
                    BigDecimal avg = rs.getBigDecimal("avg_days");
                    return new AnalyticsSummary(received, rs.getLong("demo"), resolved,
                            Rate.of(rs.getLong("on_time"), resolved),
                            avg == null ? null : avg.setScale(1, RoundingMode.HALF_UP).doubleValue(),
                            rs.getLong("overdue_open"),
                            Rate.of(rs.getLong("satisfied"), rs.getLong("with_outcome")),
                            Rate.of(rs.getLong("transferred"), received),
                            Rate.of(rs.getLong("repeated"), received),
                            Rate.of(rs.getLong("manual"), received),
                            Rate.of(rs.getLong("without_contract"), received),
                            Rate.of(rs.getLong("corrected"), resolved),
                            rs.getLong("pretensions"),
                            Rate.of(rs.getLong("pretensions_on_time"), rs.getLong("pretensions_resolved")),
                            rs.getLong("pretensions_overdue"));
                })
                .single();
    }

    /** Доли продуктов — от всех обращений периода: откуда приходит нагрузка, а не какой продукт хуже. */
    private List<ShareItem> products(Filter filter) {
        List<Object[]> rows = jdbc.sql("""
                        select f.product, p.label, count(*) as n
                        from appeal_facts f
                        left join insurance_product p on p.code = f.product""" + filter.where() + """

                        group by f.product, p.label, p.sort_order
                        order by p.sort_order nulls last""")
                .params(filter.params())
                .query((rs, row) -> new Object[]{rs.getString("product"), rs.getString("label"), rs.getLong("n")})
                .list();
        return shares(rows);
    }

    /** Типы проблем внутри продукта: у них одна база, поэтому сравнивать их честно. */
    private List<ShareItem> types(Filter filter) {
        List<Object[]> rows = jdbc.sql("""
                        select f.final_category || '/' || f.final_subcategory as code,
                               c.label || ' · ' || coalesce(t.label, f.final_subcategory) as label,
                               count(*) as n
                        from appeal_facts f
                        join appeal_category c on c.code = f.final_category
                        left join appeal_type t on t.category = f.final_category and t.code = f.final_subcategory""" + filter.where() + """

                        group by 1, 2
                        order by n desc, 2""")
                .params(filter.params())
                .query((rs, row) -> new Object[]{rs.getString("code"), rs.getString("label"), rs.getLong("n")})
                .list();
        return shares(rows);
    }

    /** Поступление по неделям — линия на каждый продукт; пустые недели — нули, а не пропуски. */
    private List<WeekSeries> weekly(Filter filter, List<LocalDate> weeks) {
        Map<String, WeekSeries> series = new LinkedHashMap<>();
        Map<LocalDate, Integer> index = new HashMap<>();
        for (int i = 0; i < weeks.size(); i++) {
            index.put(weeks.get(i), i);
        }
        jdbc.sql("""
                        select date_trunc('week', f.registered_on)::date as week, f.product, p.label, count(*) as n
                        from appeal_facts f
                        left join insurance_product p on p.code = f.product""" + filter.where() + """

                        group by 1, f.product, p.label, p.sort_order
                        order by p.sort_order nulls last, 1""")
                .params(filter.params())
                .query(rs -> {
                    String code = rs.getString("product");
                    String label = rs.getString("label");
                    WeekSeries line = series.computeIfAbsent(String.valueOf(code), key ->
                            new WeekSeries(code, label == null ? NO_PRODUCT : label, new long[weeks.size()]));
                    Integer at = index.get(rs.getDate("week").toLocalDate());
                    if (at != null) {
                        line.counts()[at] = rs.getLong("n");
                    }
                });
        return List.copyOf(series.values());
    }

    /** Доля решённых в срок по закону — по подразделениям: где не справляются. */
    private List<DepartmentOnTime> departments(Filter filter) {
        return jdbc.sql("""
                        select d.code, d.name,
                               count(*) filter (where f.resolved_on is not null) as resolved,
                               count(*) filter (where f.resolved_on_time) as on_time,
                               count(*) filter (where f.resolved_on is null and f.status not in ('RESOLVED', 'CLOSED')) as open,
                               count(*) filter (where f.overdue_open) as overdue_open
                        from appeal_facts f
                        join department d on d.code = f.department""" + filter.where() + """

                        group by d.code, d.name
                        order by d.name""")
                .params(filter.params())
                .query((rs, row) -> new DepartmentOnTime(rs.getString("code"), rs.getString("name"),
                        Rate.of(rs.getLong("on_time"), rs.getLong("resolved")),
                        rs.getLong("open"), rs.getLong("overdue_open")))
                .list();
    }

    /** Одобренные передачи «откуда → куда»: устойчивый поток — признак ошибочного правила маршрутизации. */
    private List<TransferFlow> transfers(Filter filter) {
        return jdbc.sql("""
                        select s.name as source, t.name as target, count(*) as n
                        from transfer_request r
                        join appeal_facts f on f.id = r.appeal_id
                        join department s on s.id = r.source_department_id
                        join department t on t.id = r.target_department_id""" + filter.where() + """

                          and r.status = 'APPROVED'
                        group by s.name, t.name
                        order by n desc, s.name, t.name""")
                .params(filter.params())
                .query((rs, row) -> new TransferFlow(rs.getString("source"), rs.getString("target"), rs.getLong("n")))
                .list();
    }

    /** Исходный тип → итоговый: клиенты не понимают категории формы. */
    private List<TypeCorrection> corrections(Filter filter) {
        return jdbc.sql("""
                        select ic.label || ' · ' || coalesce(it.label, f.subcategory) as initial,
                               fc.label || ' · ' || coalesce(ft.label, f.final_subcategory) as final,
                               count(*) as n
                        from appeal_facts f
                        join appeal_category ic on ic.code = f.category
                        left join appeal_type it on it.category = f.category and it.code = f.subcategory
                        join appeal_category fc on fc.code = f.final_category
                        left join appeal_type ft on ft.category = f.final_category and ft.code = f.final_subcategory""" + filter.where() + """

                          and f.type_corrected
                        group by 1, 2
                        order by n desc, 1, 2""")
                .params(filter.params())
                .query((rs, row) -> new TypeCorrection(rs.getString("initial"), rs.getString("final"), rs.getLong("n")))
                .list();
    }

    /** Подсказки помощника (решение 35): по доле принятых решают, вкладываться ли в локальную модель. */
    private List<AssistantUsage> assistant(Filter filter) {
        return jdbc.sql("""
                        select s.kind, s.source, count(*) as shown,
                               count(*) filter (where s.decision = 'ACCEPTED') as accepted,
                               count(*) filter (where s.decision = 'REJECTED') as rejected
                        from assistant_suggestion s
                        join appeal_facts f on f.id = s.appeal_id""" + filter.where() + """

                        group by s.kind, s.source
                        order by s.kind, s.source""")
                .params(filter.params())
                .query((rs, row) -> new AssistantUsage(rs.getString("kind"), rs.getString("source"), rs.getLong("shown"),
                        Rate.of(rs.getLong("accepted"), rs.getLong("shown")), rs.getLong("rejected")))
                .list();
    }

    /** Каналы поступления (решение 38): сколько обращений приходит через контакт-центр. */
    private List<ShareItem> channels(Filter filter) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("select f.channel, count(*) as n from appeal_facts f" + filter.where() + " group by f.channel")
                .params(filter.params())
                .query(rs -> {
                    counts.put(rs.getString("channel"), rs.getLong("n"));
                });
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return Arrays.stream(AppealChannel.values())
                .map(channel -> new ShareItem(channel.name(), channel.label(),
                        Rate.of(counts.getOrDefault(channel.name(), 0L), total)))
                .toList();
    }

    private List<ShareItem> outcomes(Filter filter) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("select f.outcome, count(*) as n from appeal_facts f" + filter.where()
                        + " and f.outcome is not null group by f.outcome")
                .params(filter.params())
                .query(rs -> {
                    counts.put(rs.getString("outcome"), rs.getLong("n"));
                });
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return Arrays.stream(AppealOutcome.values())
                .map(outcome -> new ShareItem(outcome.name(), outcome.label(),
                        Rate.of(counts.getOrDefault(outcome.name(), 0L), total)))
                .toList();
    }

    private static List<ShareItem> shares(List<Object[]> rows) {
        long total = rows.stream().mapToLong(row -> (Long) row[2]).sum();
        return rows.stream()
                .map(row -> new ShareItem((String) row[0], row[1] == null ? NO_PRODUCT : (String) row[1],
                        Rate.of((Long) row[2], total)))
                .toList();
    }

    private boolean productExists(String code) {
        return jdbc.sql("select count(*) from insurance_product where code = :code")
                .param("code", code).query(Long.class).single() > 0;
    }

    /** Период и выбранный продукт; продукт наверху дашборда фильтрует все панели, кроме долей продуктов. */
    private record Filter(LocalDate from, String product) {

        String where() {
            return " where f.registered_on >= :from" + (product == null ? "" : " and f.product = :product");
        }

        Map<String, Object> params() {
            Map<String, Object> params = new HashMap<>();
            params.put("from", Date.valueOf(from));
            if (product != null) {
                params.put("product", product);
            }
            return params;
        }
    }
}
