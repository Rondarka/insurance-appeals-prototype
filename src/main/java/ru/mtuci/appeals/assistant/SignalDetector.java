package ru.mtuci.appeals.assistant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.mtuci.appeals.api.ApiModels.SignalBadge;
import ru.mtuci.appeals.api.ApiModels.SignalResponse;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сигналы в тексте клиента (решение 35): грозит жалобой в Банк России или судом, упоминает
 * финансового уполномоченного, требует выплату с суммой. Признаки — справочник text_signal,
 * как схема формы. Сигналы ищутся в тексте обращения и в каждом сообщении клиента и
 * сохраняются с цитатой: очередь поднимает такие обращения, не перечитывая переписку.
 */
@Component
public class SignalDetector {

    private final JdbcClient jdbc;
    private volatile List<Rule> rules;

    public SignalDetector(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Найти сигналы в тексте клиента и запомнить новые; уже найденный сигнал не дублируется. */
    public void record(UUID appealId, String text, Instant at) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (Rule rule : rules()) {
            Matcher match = rule.pattern().matcher(text);
            if (match.find()) {
                jdbc.sql("""
                                insert into appeal_signal (appeal_id, signal_code, excerpt, detected_at)
                                values (:appeal, :code, :excerpt, :at)
                                on conflict (appeal_id, signal_code) do nothing""")
                        .param("appeal", appealId).param("code", rule.code())
                        .param("excerpt", Excerpts.around(text, match)).param("at", Timestamp.from(at))
                        .update();
            }
        }
    }

    /** Сигналы обращения с цитатой и подсказкой, что делать. */
    public List<SignalResponse> of(UUID appealId) {
        return jdbc.sql("""
                        select s.code, s.label, s.hint, s.risk, a.excerpt from appeal_signal a
                        join text_signal s on s.code = a.signal_code
                        where a.appeal_id = :appeal
                        order by s.sort_order""")
                .param("appeal", appealId)
                .query((rs, row) -> new SignalResponse(rs.getString("code"), rs.getString("label"), rs.getString("hint"),
                        rs.getBoolean("risk"), rs.getString("excerpt")))
                .list();
    }

    /** Для очереди: метки сигналов по обращениям одним запросом. */
    public Map<UUID, List<SignalBadge>> badges(Collection<UUID> appealIds) {
        Map<UUID, List<SignalBadge>> badges = new HashMap<>();
        if (appealIds.isEmpty()) {
            return badges;
        }
        jdbc.sql("""
                        select a.appeal_id, s.code, s.label, s.risk from appeal_signal a
                        join text_signal s on s.code = a.signal_code
                        where a.appeal_id in (:ids)
                        order by s.sort_order""")
                .param("ids", appealIds)
                .query(rs -> {
                    badges.computeIfAbsent(rs.getObject("appeal_id", UUID.class), id -> new ArrayList<>())
                            .add(new SignalBadge(rs.getString("code"), rs.getString("label"), rs.getBoolean("risk")));
                });
        return badges;
    }

    private List<Rule> rules() {
        List<Rule> loaded = rules;
        if (loaded == null) {
            // справочник меняется только миграцией — достаточно прочитать один раз
            loaded = jdbc.sql("select code, pattern from text_signal order by sort_order")
                    .query((rs, row) -> new Rule(rs.getString("code"), Pattern.compile(rs.getString("pattern"), Excerpts.FLAGS)))
                    .list();
            rules = loaded;
        }
        return loaded;
    }

    private record Rule(String code, Pattern pattern) {
    }
}
