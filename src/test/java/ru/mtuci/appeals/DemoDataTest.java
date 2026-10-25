package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.demo.DemoDataGenerator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Генератор демонстрационных данных (решение 33): обращения согласованы так же, как
 * настоящие: договор, норматив, срок, итог у решённых, даты не из будущего.
 */
class DemoDataTest extends IntegrationTest {

    @Autowired
    private DemoDataGenerator generator;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void clean() {
        generator.clear();
    }

    @Test
    void generatedAppealsAreConsistent() {
        int created = generator.generate(40, 120, 7);

        assertThat(created).isEqualTo(40);
        assertThat(count("select count(*) from appeal where demo")).isEqualTo(40);
        // у каждого: договор, норматив, срок по закону, отдел и история
        assertThat(count("select count(*) from appeal where demo and (contract_id is null or norm_id is null or legal_deadline is null)")).isZero();
        assertThat(count("select count(*) from appeal a where demo and not exists (select 1 from appeal_history h where h.appeal_id = a.id)")).isZero();
        // решённые: с итогом, датой и ответом сотрудника; ничего из будущего
        assertThat(count("select count(*) from appeal where demo and status in ('RESOLVED', 'CLOSED') and (outcome is null or resolved_at is null)")).isZero();
        assertThat(count("""
                select count(*) from appeal a where demo and status in ('RESOLVED', 'CLOSED')
                  and not exists (select 1 from appeal_message m where m.appeal_id = a.id and m.author_type = 'EMPLOYEE')""")).isZero();
        assertThat(count("select count(*) from appeal where demo and (created_at > now() or updated_at > now() or resolved_at > now() or resolved_at < created_at)")).isZero();
        // есть и закрытые, и открытые обращения: очередь не пустая
        List<String> statuses = jdbc.sql("select distinct status from appeal where demo").query(String.class).list();
        assertThat(statuses).contains("RESOLVED").containsAnyOf("ROUTED", "IN_PROGRESS", "WAITING_CUSTOMER");
    }

    @Test
    void sameSeedGivesSameDataAndClearRemovesIt() {
        generator.generate(15, 90, 11);
        Map<String, Object> first = snapshot();
        generator.clear();
        assertThat(count("select count(*) from appeal where demo")).isZero();

        generator.generate(15, 90, 11);
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    void demoAppealsAppearInDepartmentQueue() {
        generator.generate(30, 60, 3);

        AppealSummaryResponse[] queue = as(CLAIMS_SUPERVISOR).getForObject("/api/appeals", AppealSummaryResponse[].class);
        long demoClaims = count("select count(*) from appeal a join department d on d.id = a.department_id where demo and d.code = 'CLAIMS'");
        assertThat(Arrays.stream(queue).filter(item -> item.department() != null && "CLAIMS".equals(item.department().code())).count())
                .isGreaterThanOrEqualTo(demoClaims);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private Map<String, Object> snapshot() {
        return Map.of(
                "subjects", jdbc.sql("select string_agg(subject, '|' order by id) from appeal where demo").query(String.class).single(),
                "statuses", jdbc.sql("select string_agg(status, '|' order by id) from appeal where demo").query(String.class).single(),
                "deadlines", jdbc.sql("select string_agg(legal_deadline::text, '|' order by id) from appeal where demo").query(String.class).single());
    }
}
