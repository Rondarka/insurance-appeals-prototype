package ru.mtuci.appeals.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.mtuci.appeals.api.ApiModels.AccessLogResponse;
import ru.mtuci.appeals.security.CurrentUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Журнал доступа к обращениям (решение 40): кто из сотрудников открывал карточку и файлы.
 * Карточка на рабочем месте обновляется каждые 10 секунд, поэтому повторный просмотр того же
 * сотрудника в течение 30 минут отдельной записью не пишется; каждое открытие файла пишется.
 * Пишется вне транзакции чтения: запись в журнал не должна делать просмотр изменяющим.
 */
@Service
public class AccessLog {

    private static final String REPEAT_WINDOW = "30 minutes";

    private final JdbcClient jdbc;

    public AccessLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void viewed(CurrentUser user, UUID appealId) {
        if (user.isClient()) {
            return;
        }
        jdbc.sql("""
                        insert into appeal_access_log (appeal_id, action, viewer_subject, viewer_name, viewer_role,
                                                       viewer_department, accessed_at)
                        select :appeal, 'VIEWED', :subject, :name, :role, :department, :at
                        where not exists (select 1 from appeal_access_log
                                          where appeal_id = :appeal and viewer_subject = :subject and action = 'VIEWED'
                                            and accessed_at > cast(:at as timestamptz) - interval '%s')""".formatted(REPEAT_WINDOW))
                .param("appeal", appealId).param("subject", user.subject()).param("name", name(user))
                .param("role", role(user)).param("department", user.departmentCode())
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    public void fileOpened(CurrentUser user, UUID appealId, UUID attachmentId) {
        if (user.isClient()) {
            return;
        }
        jdbc.sql("""
                        insert into appeal_access_log (appeal_id, action, attachment_id, viewer_subject, viewer_name,
                                                       viewer_role, viewer_department, accessed_at)
                        values (:appeal, 'FILE_OPENED', :attachment, :subject, :name, :role, :department, :at)""")
                .param("appeal", appealId).param("attachment", attachmentId).param("subject", user.subject())
                .param("name", name(user)).param("role", role(user)).param("department", user.departmentCode())
                .param("at", Timestamp.from(Instant.now()))
                .update();
    }

    /** Для администратора: последние записи по обращению, с названием файла. */
    public List<AccessLogResponse> of(UUID appealId) {
        return jdbc.sql("""
                        select l.action, l.viewer_name, l.viewer_role, d.name as department, l.accessed_at,
                               f.original_name as file
                        from appeal_access_log l
                        left join department d on d.code = l.viewer_department
                        left join appeal_attachment f on f.id = l.attachment_id
                        where l.appeal_id = :appeal
                        order by l.accessed_at desc
                        limit 100""")
                .param("appeal", appealId)
                .query((rs, row) -> new AccessLogResponse(rs.getString("action"), rs.getString("viewer_name"),
                        rs.getString("viewer_role"), rs.getString("department"),
                        rs.getTimestamp("accessed_at").toInstant(), rs.getString("file")))
                .list();
    }

    private static String name(CurrentUser user) {
        return Objects.requireNonNullElse(user.name(), user.subject());
    }

    private static String role(CurrentUser user) {
        return user.roles().stream().sorted().findFirst().orElse("UNKNOWN");
    }
}
