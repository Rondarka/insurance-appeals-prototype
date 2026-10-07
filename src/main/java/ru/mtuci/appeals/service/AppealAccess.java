package ru.mtuci.appeals.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.TransferRequestStatus;
import ru.mtuci.appeals.repository.TransferRequestRepository;
import ru.mtuci.appeals.security.CurrentUser;

/**
 * Кто к какому обращению имеет доступ. Роль пускает к виду операций (SecurityConfig),
 * а здесь проверяется конкретное обращение: свои ли это данные.
 */
@Component
public class AppealAccess {

    private final TransferRequestRepository transferRepository;

    public AppealAccess(TransferRequestRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    /**
     * Клиент видит свои обращения, сотрудник — обращения своего подразделения,
     * руководитель — ещё и те, что ждут его решения о передаче в его подразделение.
     */
    public boolean canView(CurrentUser user, Appeal appeal) {
        if (user.isClient() && appeal.getCounterpartyId().equals(user.counterpartyId())) {
            return true;
        }
        if (inDepartment(user, appeal)) {
            return true;
        }
        return user.isSupervisor() && transferRepository.existsByAppealIdAndTargetDepartmentCodeAndStatus(
                appeal.getId(), user.departmentCode(), TransferRequestStatus.PENDING);
    }

    public void checkView(CurrentUser user, Appeal appeal) {
        if (!canView(user, appeal)) {
            throw new AppealNotFoundException(appeal.getId());
        }
    }

    /** Работать с обращением — менять статус, отвечать, передавать — может сотрудник его подразделения. */
    public void checkWork(CurrentUser user, Appeal appeal) {
        checkView(user, appeal);
        if (!inDepartment(user, appeal)) {
            throw new AccessDeniedException("Работать с обращением может только сотрудник его подразделения");
        }
    }

    private static boolean inDepartment(CurrentUser user, Appeal appeal) {
        return user.isEmployee()
                && appeal.getDepartment() != null
                && appeal.getDepartment().getCode().equals(user.departmentCode());
    }
}
