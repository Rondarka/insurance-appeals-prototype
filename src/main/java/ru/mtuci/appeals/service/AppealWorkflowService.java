package ru.mtuci.appeals.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ExtendDeadlineRequest;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.api.ApiModels.StaffMemberResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealMessage;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;
import ru.mtuci.appeals.messaging.AppealDomainEvent;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealMessageRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Работа с обращением в отделе: исполнитель, смена статуса, ответ по существу с итогом и
 * продление срока по закону. Каждое действие пишется в журнал обработки и публикуется
 * событием; кто что может, проверяет AppealAccess.
 */
@Service
public class AppealWorkflowService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final AppealRepository appealRepository;
    private final AppealHistoryRepository historyRepository;
    private final AppealMessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AppealFormPolicy formPolicy;
    private final AppealAccess access;
    private final ProductionCalendar calendar;
    private final StaffService staffService;
    private final AppealViews views;
    private final ZoneId zone;

    public AppealWorkflowService(AppealRepository appealRepository, AppealHistoryRepository historyRepository,
                                 AppealMessageRepository messageRepository, ApplicationEventPublisher eventPublisher,
                                 AppealFormPolicy formPolicy, AppealAccess access, ProductionCalendar calendar,
                                 StaffService staffService, AppealViews views,
                                 @Value("${appeals.timezone}") String timezone) {
        this.appealRepository = appealRepository;
        this.historyRepository = historyRepository;
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
        this.formPolicy = formPolicy;
        this.access = access;
        this.calendar = calendar;
        this.staffService = staffService;
        this.views = views;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * Назначить исполнителя — руководитель отдела выбирает из сотрудников своего отдела,
     * которые входили в подсистему (решение 32).
     */
    @Transactional
    public AppealDetailResponse assign(CurrentUser user, UUID id, String subject) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        if (!user.isSupervisor()) {
            throw new AccessDeniedException("Назначить исполнителя может руководитель отдела");
        }
        if (appeal.getStatus() == AppealStatus.RESOLVED || appeal.getStatus() == AppealStatus.CLOSED) {
            throw new IllegalStateException("Обращение уже решено");
        }
        StaffMemberResponse assignee = staffService.find(subject)
                .filter(member -> member.departmentCode().equals(appeal.getDepartment().getCode()))
                .orElseThrow(() -> new IllegalArgumentException("Сотрудник не найден в отделе «"
                        + appeal.getDepartment().getName() + "»"));
        Instant now = Instant.now();
        appeal.assign(assignee.id(), assignee.fullName(), now);
        historyRepository.save(new AppealHistory(
                appeal,
                "ASSIGNED",
                "Назначен исполнитель: " + assignee.fullName(),
                Objects.requireNonNullElse(user.name(), user.subject()),
                now
        ));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_ASSIGNED",
                appeal.getId(),
                "appeal.assigned." + appeal.getDepartment().getCode().toLowerCase(Locale.ROOT),
                // наружу — идентификатор сотрудника, а не имя (решение 22)
                Map.of("assignee", assignee.id())
        ));
        return views.toDetail(appeal);
    }

    @Transactional
    public AppealDetailResponse changeStatus(CurrentUser user, UUID id, ChangeStatusRequest request) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        String employeeName = Objects.requireNonNullElse(user.name(), user.subject());

        Instant now = Instant.now();
        AppealStatus previous = appeal.getStatus();
        appeal.changeStatus(request.status(), user.subject(), employeeName, now);

        historyRepository.save(new AppealHistory(
                appeal,
                "STATUS_CHANGED",
                "Статус изменён: «" + previous.label() + "» → «" + request.status().label() + "»",
                employeeName,
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_STATUS_CHANGED",
                appeal.getId(),
                "appeal.status." + request.status().name().toLowerCase(Locale.ROOT),
                Map.of(
                        "fromStatus", previous.name(),
                        "toStatus", request.status().name()
                )
        ));

        return views.toDetail(appeal);
    }

    /**
     * Решить обращение — значит ответить клиенту по существу: результат и обоснование уходят
     * в переписку, у обращения появляется итог. Без ответа решить нельзя (4015-1, ст. 6.2).
     */
    @Transactional
    public AppealDetailResponse resolve(CurrentUser user, UUID id, ResolveAppealRequest request) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        String employeeName = Objects.requireNonNullElse(user.name(), user.subject());
        Instant now = Instant.now();
        AppealStatus previous = appeal.getStatus();
        String finalCategory = blank(request.finalCategory()) ? appeal.getCategory() : request.finalCategory();
        String finalSubcategory = blank(request.finalSubcategory()) ? appeal.getSubcategory() : request.finalSubcategory();
        if (!formPolicy.typeExists(finalCategory, finalSubcategory)) {
            throw new IllegalArgumentException("Неизвестный тип обращения: " + finalCategory + "/" + finalSubcategory);
        }
        appeal.resolve(request.outcome(), finalCategory, finalSubcategory, now);

        messageRepository.save(new AppealMessage(appeal, AuthorType.EMPLOYEE, employeeName, request.answer().trim(), now));
        historyRepository.save(new AppealHistory(
                appeal,
                "RESOLVED",
                "Клиенту направлен ответ по существу. Итог: " + request.outcome().label().toLowerCase(Locale.ROOT),
                employeeName,
                now
        ));

        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_MESSAGE_ADDED",
                appeal.getId(),
                "appeal.message.employee",
                Map.of("authorType", AuthorType.EMPLOYEE.name())
        ));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_STATUS_CHANGED",
                appeal.getId(),
                "appeal.status.resolved",
                Map.of(
                        "fromStatus", previous.name(),
                        "toStatus", AppealStatus.RESOLVED.name(),
                        "outcome", request.outcome().name()
                )
        ));
        return views.toDetail(appeal);
    }

    /**
     * Продление срока по закону — решение руководителя отдела (Закон № 4015-1, ст. 6.2 п. 5).
     * Новый срок отсчитывается рабочими днями от прежнего, клиент получает уведомление
     * с обоснованием в переписке.
     */
    @Transactional
    public AppealDetailResponse extendDeadline(CurrentUser user, UUID id, ExtendDeadlineRequest request) {
        Appeal appeal = findAppeal(id);
        access.checkWork(user, appeal);
        if (!user.isSupervisor()) {
            throw new AccessDeniedException("Продлить срок может руководитель отдела");
        }
        String supervisorName = Objects.requireNonNullElse(user.name(), user.subject());
        Instant now = Instant.now();
        String reason = request.reason().trim();
        LocalDate newDeadline = appeal.getLegalDeadline() == null
                ? null
                : calendar.addWorkingDays(appeal.getLegalDeadline(), request.days());
        appeal.extendLegalDeadline(request.days(), newDeadline, reason, LocalDate.now(zone), now);

        messageRepository.save(new AppealMessage(appeal, AuthorType.EMPLOYEE, supervisorName,
                "Срок рассмотрения вашего обращения продлён на " + request.days() + " раб. дн. — до "
                        + DAY.format(newDeadline) + ".\nПричина: " + reason,
                now));
        historyRepository.save(new AppealHistory(
                appeal,
                "DEADLINE_EXTENDED",
                "Срок по закону продлён на " + request.days() + " раб. дн., до " + DAY.format(newDeadline)
                        + ". Причина: " + reason,
                supervisorName,
                now
        ));
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_DEADLINE_EXTENDED",
                appeal.getId(),
                "appeal.deadline.extended",
                Map.of("days", Integer.toString(request.days()), "legalDeadline", newDeadline.toString())
        ));
        return views.toDetail(appeal);
    }

    private Appeal findAppeal(UUID id) {
        return appealRepository.findById(id)
                .orElseThrow(() -> new AppealNotFoundException(id));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
