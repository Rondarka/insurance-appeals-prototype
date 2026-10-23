package ru.mtuci.appeals.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.ClassifiedResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealPriority;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.repository.RoutingRuleRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ручная классификация (решение 31): для типа обращения нет правила маршрутизации —
 * отдел выбирает руководитель. Обращение уходит туда так же, как при автоматической
 * маршрутизации: с целевым сроком отдела и событием appeal.routed.
 */
@Service
public class ClassificationService {

    private static final int DEFAULT_SLA_HOURS = 24;

    private final AppealRepository appealRepository;
    private final DepartmentRepository departmentRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AppealAccess access;
    private final ZoneId zone;

    public ClassificationService(AppealRepository appealRepository,
                                 DepartmentRepository departmentRepository,
                                 RoutingRuleRepository routingRuleRepository,
                                 AppealHistoryRepository historyRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 AppealAccess access,
                                 @Value("${appeals.timezone}") String timezone) {
        this.appealRepository = appealRepository;
        this.departmentRepository = departmentRepository;
        this.routingRuleRepository = routingRuleRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.access = access;
        this.zone = ZoneId.of(timezone);
    }

    @Transactional
    public ClassifiedResponse classify(CurrentUser user, UUID id, String departmentCode) {
        if (!user.isSupervisor()) {
            throw new AccessDeniedException("Направить обращение в отдел может руководитель");
        }
        Appeal appeal = appealRepository.findById(id).orElseThrow(() -> new AppealNotFoundException(id));
        // уже направленное в чужой отдел обращение руководитель не видит — для него его нет
        access.checkView(user, appeal);
        if (appeal.getStatus() != AppealStatus.NEEDS_CLASSIFICATION) {
            throw new IllegalStateException("Обращение уже направлено в отдел");
        }
        Department department = departmentRepository.findByCode(departmentCode)
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный отдел: " + departmentCode));

        // срок отдела — как при передаче: самый короткий из правил этого отдела
        int slaHours = routingRuleRepository.findFirstByDepartmentCodeOrderBySlaHoursAsc(department.getCode())
                .map(rule -> rule.getSlaHours())
                .orElse(DEFAULT_SLA_HOURS);
        Instant now = Instant.now();
        appeal.routeTo(department, AppealPriority.NORMAL,
                appeal.withinLegalDeadline(now.plus(slaHours, ChronoUnit.HOURS), zone), now);

        String supervisor = Objects.requireNonNullElse(user.name(), user.subject());
        historyRepository.save(new AppealHistory(
                appeal,
                "CLASSIFIED",
                "Направлено в отдел «" + department.getName() + "» вручную: правила маршрутизации для этого типа нет. SLA: "
                        + slaHours + " ч.",
                supervisor,
                now
        ));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_ROUTED",
                appeal.getId(),
                "appeal.routed." + department.getCode().toLowerCase(Locale.ROOT),
                Map.of(
                        "departmentCode", department.getCode(),
                        "subcategory", appeal.getSubcategory(),
                        "priority", AppealPriority.NORMAL.name(),
                        "slaHours", Integer.toString(slaHours),
                        "manual", "true"
                )
        ));
        return new ClassifiedResponse(department.getCode(), department.getName());
    }
}
