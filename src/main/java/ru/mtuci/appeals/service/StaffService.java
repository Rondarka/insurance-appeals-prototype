package ru.mtuci.appeals.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.mtuci.appeals.api.ApiModels.StaffMemberResponse;
import ru.mtuci.appeals.security.CurrentUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Сотрудники, которых руководитель может назначить исполнителем (решение 32).
 *
 * Справочника сотрудников в подсистеме нет (решение 22): кто есть кто, сообщает IdP в токене.
 * Чтобы руководителю было из кого выбирать, подсистема запоминает последнее, что IdP сообщил
 * о сотруднике, когда тот открыл рабочее место: имя, отдел, роль. Сотрудник, ни разу не
 * входивший, в списке не появится: он и не может работать с обращениями. В промышленной
 * версии список отдела запрашивается у каталога сотрудников (служебная учётная запись IdP).
 */
@Service
public class StaffService {

    private final JdbcClient jdbc;

    public StaffService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Отметить сотрудника по его токену: имя и отдел обновляются при каждом входе. */
    public void remember(CurrentUser user) {
        if (!user.isEmployee() || user.departmentCode() == null) {
            return;
        }
        jdbc.sql("""
                        insert into staff_member (subject, full_name, department_code, supervisor, last_seen_at)
                        values (:subject, :name, :department, :supervisor, :now)
                        on conflict (subject) do update
                        set full_name = excluded.full_name,
                            department_code = excluded.department_code,
                            supervisor = excluded.supervisor,
                            last_seen_at = excluded.last_seen_at""")
                .param("subject", user.subject())
                .param("name", Objects.requireNonNullElse(user.name(), user.subject()))
                .param("department", user.departmentCode())
                .param("supervisor", user.isSupervisor())
                .param("now", Timestamp.from(Instant.now()))
                .update();
        // обращения, взятые в работу до появления идентификатора исполнителя, ищутся по имени в своём отделе
        jdbc.sql("""
                        update appeal set assigned_subject = :subject
                        where assigned_subject is null
                          and assigned_employee = :name
                          and department_id = (select id from department where code = :department)""")
                .param("subject", user.subject())
                .param("name", Objects.requireNonNullElse(user.name(), user.subject()))
                .param("department", user.departmentCode())
                .update();
    }

    public List<StaffMemberResponse> department(String departmentCode) {
        return jdbc.sql("""
                        select subject as id, full_name, supervisor, department_code from staff_member
                        where department_code = :department
                        order by supervisor, full_name""")
                .param("department", departmentCode)
                .query(StaffMemberResponse.class)
                .list();
    }

    public Optional<StaffMemberResponse> find(String subject) {
        return jdbc.sql("select subject as id, full_name, supervisor, department_code from staff_member where subject = :subject")
                .param("subject", subject)
                .query((rs, rowNum) -> new StaffMemberResponse(rs.getString("id"), rs.getString("full_name"),
                        rs.getBoolean("supervisor"), rs.getString("department_code")))
                .optional();
    }
}
