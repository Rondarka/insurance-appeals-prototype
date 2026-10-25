package ru.mtuci.appeals.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.AppealHistory;
import ru.mtuci.appeals.messaging.CounterpartyMerged;
import ru.mtuci.appeals.repository.AppealHistoryRepository;
import ru.mtuci.appeals.repository.AppealRepository;

import java.time.Instant;
import java.util.List;

/**
 * Объединение дублей клиента (решение 44). Один человек мог получить в CDI две карточки,
 * например, оформив договор в офисе и зарегистрировавшись в кабинете. CDI их объединяет, и
 * обращения дубля переходят на основную карточку: клиент видит их все в одном кабинете,
 * сотрудник видит полную историю клиента, повторные обращения считаются верно.
 */
@Service
public class CounterpartyMergeService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyMergeService.class);
    private static final String ACTOR = "Справочник клиентов (CDI)";

    private final AppealRepository appeals;
    private final AppealHistoryRepository history;

    public CounterpartyMergeService(AppealRepository appeals, AppealHistoryRepository history) {
        this.appeals = appeals;
        this.history = history;
    }

    /**
     * Повтор того же события ничего не меняет: у дубля обращений уже нет. Событие без
     * идентификаторов или со слиянием карточки в саму себя уходит в очередь отказов.
     */
    @Transactional
    public int merge(CounterpartyMerged event) {
        if (event.duplicateId() == null || event.masterId() == null || event.duplicateId().equals(event.masterId())) {
            throw new IllegalArgumentException("Событие объединения без двух разных карточек клиента");
        }
        Instant now = Instant.now();
        List<Appeal> moved = appeals.findByCounterpartyIdOrderByCreatedAtDesc(event.duplicateId());
        for (Appeal appeal : moved) {
            appeal.moveToCounterparty(event.masterId(), now);
            history.save(new AppealHistory(appeal, "COUNTERPARTY_MERGED",
                    "Карточки клиента объединены в CDI: обращение перенесено на основную карточку", ACTOR, now));
        }
        log.info("Карточка {} объединена с {}: перенесено обращений {}", event.duplicateId(), event.masterId(), moved.size());
        return moved.size();
    }
}
