package ru.mtuci.appeals.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.EscalationLevel;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Эскалация по сроку закона (решение 14) — цепочка, которую запускает время, а не действие
 * пользователя. Планировщик отбирает нерешённые обращения, у которых срок по закону близко
 * или уже прошёл, поднимает уровень эскалации и приоритет и публикует событие: по нему
 * существующая система уведомляет руководителя отдела.
 */
@Service
public class EscalationService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final List<AppealStatus> FINISHED = List.of(AppealStatus.RESOLVED, AppealStatus.CLOSED);

    private final AppealRepository appealRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductionCalendar calendar;
    private final TransactionTemplate transactions;
    private final ZoneId zone;
    private final int warnWorkingDays;

    public EscalationService(AppealRepository appealRepository,
                             AppealHistoryRepository historyRepository,
                             ApplicationEventPublisher eventPublisher,
                             ProductionCalendar calendar,
                             PlatformTransactionManager transactionManager,
                             @Value("${appeals.timezone}") String timezone,
                             @Value("${appeals.escalation.warn-working-days}") int warnWorkingDays) {
        this.appealRepository = appealRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.calendar = calendar;
        this.transactions = new TransactionTemplate(transactionManager);
        this.zone = ZoneId.of(timezone);
        this.warnWorkingDays = warnWorkingDays;
    }

    @Scheduled(initialDelayString = "${appeals.escalation.initial-delay}",
            fixedDelayString = "${appeals.escalation.interval}")
    public void scheduledScan() {
        scan(LocalDate.now(zone));
    }

    /**
     * Один проход планировщика на дату today. Каждое обращение — в своей транзакции: если его
     * в этот момент меняет сотрудник или другой экземпляр подсистемы, оптимистическая
     * блокировка не даст поднять уровень дважды, обращение разберёт следующий проход.
     *
     * @return у скольких обращений поднялся уровень
     */
    public int scan(LocalDate today) {
        LocalDate horizon = calendar.addWorkingDays(today, warnWorkingDays);
        List<UUID> candidates = appealRepository.findEscalationCandidates(horizon, FINISHED, EscalationLevel.BREACHED);
        int raised = 0;
        for (UUID id : candidates) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> escalate(id, today, horizon)))) {
                    raised++;
                }
            } catch (OptimisticLockingFailureException exception) {
                // обращение изменили параллельно — вернёмся к нему на следующем проходе
            }
        }
        return raised;
    }

    private boolean escalate(UUID id, LocalDate today, LocalDate horizon) {
        Appeal appeal = appealRepository.findById(id).orElse(null);
        if (appeal == null || appeal.getLegalDeadline() == null || FINISHED.contains(appeal.getStatus())
                || appeal.getLegalDeadline().isAfter(horizon)) {
            return false;
        }
        LocalDate deadline = appeal.getLegalDeadline();
        EscalationLevel level = today.isAfter(deadline) ? EscalationLevel.BREACHED : EscalationLevel.AT_RISK;
        Instant now = Instant.now();
        if (!appeal.escalate(level, now)) {
            return false;
        }

        String description;
        if (level == EscalationLevel.BREACHED) {
            description = "Срок по закону нарушен: истёк " + DAY.format(deadline) + ". Приоритет — критический";
        } else {
            int left = calendar.workingDaysAfter(today, deadline);
            description = "Срок по закону истекает " + DAY.format(deadline) + " — "
                    + (left == 0 ? "сегодня последний день." : "осталось " + left + " раб. дн.")
                    + " Приоритет повышен, руководитель отдела предупреждён";
        }
        historyRepository.save(new AppealHistory(appeal, "ESCALATED", description, "Подсистема", now));

        String department = appeal.getDepartment() == null
                ? "unrouted"
                : appeal.getDepartment().getCode().toLowerCase(Locale.ROOT);
        boolean breached = level == EscalationLevel.BREACHED;
        eventPublisher.publishEvent(new AppealDomainEvent(
                breached ? "APPEAL_SLA_BREACHED" : "APPEAL_SLA_BREACHING",
                appeal.getId(),
                (breached ? "appeal.sla.breached." : "appeal.sla.breaching.") + department,
                Map.of("departmentCode", department.toUpperCase(Locale.ROOT),
                        "legalDeadline", deadline.toString(),
                        "priority", appeal.getPriority().name())
        ));
        return true;
    }
}
