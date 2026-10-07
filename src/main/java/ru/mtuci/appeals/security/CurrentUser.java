package ru.mtuci.appeals.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Кто делает запрос. Берётся только из проверенного токена поставщика идентификации
 * (решение 22): подставить чужой идентификатор в запрос нельзя — его там нет.
 *
 * @param counterpartyId идентификатор контрагента в учётной системе — у клиентов
 * @param departmentCode код подразделения — у сотрудников
 */
public record CurrentUser(
        String subject,
        String name,
        String email,
        Set<String> roles,
        UUID counterpartyId,
        String departmentCode
) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        String counterparty = jwt.getClaimAsString("counterparty_id");
        return new CurrentUser(
                jwt.getSubject(),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("email"),
                roles == null ? Set.of() : Set.copyOf(roles),
                counterparty == null ? null : UUID.fromString(counterparty),
                jwt.getClaimAsString("department")
        );
    }

    public boolean isClient() {
        return roles.contains("CLIENT");
    }

    public boolean isEmployee() {
        return roles.contains("SPECIALIST") || roles.contains("SUPERVISOR");
    }

    public boolean isSupervisor() {
        return roles.contains("SUPERVISOR");
    }

    /** Клиент без counterparty_id — ошибка настройки IdP, а не повод взять идентификатор из запроса. */
    public UUID requireCounterparty() {
        if (!isClient() || counterpartyId == null) {
            throw new AccessDeniedException("В токене нет идентификатора контрагента");
        }
        return counterpartyId;
    }

    public String requireDepartment() {
        if (!isEmployee() || departmentCode == null) {
            throw new AccessDeniedException("В токене нет подразделения сотрудника");
        }
        return departmentCode;
    }

    /** Сотрудник в справочнике подсистемы заведён под тем же идентификатором, что в IdP. */
    public UUID employeeId() {
        return UUID.fromString(subject);
    }
}
