package ru.mtuci.appeals.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.api.ApiModels.RetentionResponse;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сроки хранения персональных данных (решение 41). Когда с ответа по существу прошло
 * заданное число лет, персональные данные в обращении стираются: имя и контакты клиента,
 * тема и текст, переписка, данные формы, файлы, сигналы с цитатами, привязка к клиенту и
 * договору. Остаются тип, продукт, отдел, даты, срок по закону и итог: на них стоит
 * аналитика, и персональных данных в них нет.
 *
 * Стирается необратимо, поэтому только у решённых и закрытых обращений и пачками по 100 в
 * отдельной транзакции: сбой на одной пачке не откатывает уже сделанное.
 */
@Service
public class RetentionService {

    static final String ANONYMOUS = "Клиент обезличен";
    static final String REMOVED = "Удалено по сроку хранения";
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final AttachmentService attachments;
    private final TransactionTemplate transactions;

    public RetentionService(JdbcClient jdbc, AttachmentService attachments, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.attachments = attachments;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelayString = "${appeals.retention.initial-delay}", fixedDelayString = "${appeals.retention.interval}")
    @SchedulerLock(name = "retention")
    public void scheduledAnonymize() {
        int done = anonymizeResolvedBefore(border(years()));
        if (done > 0) {
            log.info("Обезличено обращений по сроку хранения: {}", done);
        }
    }

    /** Обезличить обращения, ответ по которым дан раньше border; возвращает, сколько обезличено. */
    public int anonymizeResolvedBefore(Instant border) {
        int total = 0;
        while (true) {
            List<String> files = new ArrayList<>();
            Integer done = transactions.execute(status -> {
                List<UUID> batch = jdbc.sql("""
                                select id from appeal
                                where anonymized_at is null and resolved_at < :border and status in ('RESOLVED', 'CLOSED')
                                order by resolved_at
                                limit :limit
                                for update skip locked""")
                        .param("border", Timestamp.from(border)).param("limit", BATCH)
                        .query(UUID.class).list();
                if (!batch.isEmpty()) {
                    files.addAll(anonymize(batch));
                }
                return batch.size();
            });
            // файлы удаляются с диска только после фиксации: откат не оставит обращение без файлов
            attachments.deleteStored(files);
            total += done == null ? 0 : done;
            if (done == null || done < BATCH) {
                return total;
            }
        }
    }

    public RetentionResponse status() {
        int years = years();
        Instant border = border(years);
        return jdbc.sql("""
                        select count(*) filter (where anonymized_at is not null) as anonymized,
                               count(*) filter (where anonymized_at is null and resolved_at < :border
                                                and status in ('RESOLVED', 'CLOSED')) as due
                        from appeal""")
                .param("border", Timestamp.from(border))
                .query((rs, row) -> new RetentionResponse(years, rs.getLong("anonymized"), rs.getLong("due")))
                .single();
    }

    int years() {
        return jdbc.sql("select years from data_retention where id = 1").query(Integer.class).single();
    }

    private static Instant border(int years) {
        return Instant.now().atOffset(ZoneOffset.UTC).minusYears(years).toInstant();
    }

    /** Стереть персональные данные пачки; возвращает имена файлов в хранилище, которые надо удалить. */
    private List<String> anonymize(List<UUID> ids) {
        List<String> files = jdbc.sql("select storage_name from appeal_attachment where appeal_id in (:ids)")
                .param("ids", ids).query(String.class).list();
        jdbc.sql("delete from appeal_attachment where appeal_id in (:ids)").param("ids", ids).update();
        jdbc.sql("delete from appeal_signal where appeal_id in (:ids)").param("ids", ids).update();
        jdbc.sql("update appeal_message set body = :removed where appeal_id in (:ids)")
                .param("removed", REMOVED).param("ids", ids).update();
        // имя клиента встречается и в журнале обработки: «Добавлено сообщение от: …»
        jdbc.sql("""
                        update appeal_history h
                        set actor = case when h.actor = a.customer_name then 'Клиент' else h.actor end,
                            description = replace(h.description, a.customer_name, 'клиент')
                        from appeal a
                        where a.id = h.appeal_id and a.id in (:ids)""")
                .param("ids", ids).update();
        jdbc.sql("""
                        update appeal
                        set customer_name = :anonymous, customer_email = '', subject = :removed, description = :removed,
                            details_json = '{}', counterparty_id = gen_random_uuid(),
                            contract_id = null, contract_policy_number = null, contract_insured_object = null,
                            requested_contract_id = null, requested_policy_number = null,
                            deadline_extension_reason = null, anonymized_at = now(), version = version + 1
                        where id in (:ids)""")
                .param("anonymous", ANONYMOUS).param("removed", REMOVED).param("ids", ids).update();
        return files;
    }
}
