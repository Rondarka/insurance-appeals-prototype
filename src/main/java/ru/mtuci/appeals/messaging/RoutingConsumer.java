package ru.mtuci.appeals.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.RoutingRule;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;
import ru.mtuci.appeals.repository.RoutingRuleRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Component
public class RoutingConsumer {

    private static final Logger log = LoggerFactory.getLogger(RoutingConsumer.class);

    private final AppealRepository appealRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final AppealHistoryRepository historyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RoutingConsumer(AppealRepository appealRepository,
                           RoutingRuleRepository routingRuleRepository,
                           AppealHistoryRepository historyRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.appealRepository = appealRepository;
        this.routingRuleRepository = routingRuleRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
    }

    @RabbitListener(queues = RabbitTopology.ROUTING_QUEUE)
    @Transactional
    public void route(AppealEvent event) {
        Appeal appeal = appealRepository.findById(event.appealId())
                .orElseThrow(() -> new IllegalStateException("Appeal not found: " + event.appealId()));

        if (appeal.getStatus() != AppealStatus.PENDING_ROUTING) {
            log.info("Skipping duplicate routing event {} for appeal {}", event.eventId(), appeal.getId());
            return;
        }

        RoutingRule rule = routingRuleRepository
                .findByCategoryAndSubcategory(appeal.getCategory(), appeal.getSubcategory())
                .or(() -> routingRuleRepository.findByCategoryAndSubcategory(
                        appeal.getCategory(), "GENERAL"))
                .orElseThrow(() -> new IllegalStateException(
                        "No routing rule for category " + appeal.getCategory()
                                + " and subcategory " + appeal.getSubcategory()));

        Instant now = Instant.now();
        Instant deadline = now.plus(rule.getSlaHours(), ChronoUnit.HOURS);
        appeal.routeTo(rule.getDepartment(), rule.getPriority(), deadline, now);

        historyRepository.save(new AppealHistory(
                appeal,
                "ROUTED",
                "Обращение автоматически направлено в отдел «" + rule.getDepartment().getName()
                        + "». SLA: " + rule.getSlaHours() + " ч.",
                "Сервис маршрутизации",
                now
        ));

        String departmentCode = rule.getDepartment().getCode().toLowerCase();
        eventPublisher.publishEvent(new AppealDomainEvent(
                "APPEAL_ROUTED",
                appeal.getId(),
                "appeal.routed." + departmentCode,
                Map.of(
                        "departmentCode", rule.getDepartment().getCode(),
                        "subcategory", appeal.getSubcategory(),
                        "priority", rule.getPriority().name(),
                        "slaHours", Integer.toString(rule.getSlaHours())
                )
        ));
    }
}
