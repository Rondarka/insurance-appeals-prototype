package ru.mtuci.appeals.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.mtuci.appeals.domain.DeadlineNorm;
import ru.mtuci.appeals.repository.DeadlineNormRepository;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Какой нормативный срок применяется к обращению (решения 20, 21). Выбор зависит от
 * типа обращения, продукта договора и — для претензии — от данных самого обращения.
 * Значения сроков в справочнике, здесь только правила выбора.
 */
@Service
public class DeadlineNormService {

    /** Норматив, если для типа ничего не задано, — общий срок ответа на обращение. */
    private static final String GENERAL = "GENERAL";

    private record Mapping(String productCode, String normCode, int priority) {
    }

    private final JdbcClient jdbc;
    private final DeadlineNormRepository repository;

    public DeadlineNormService(JdbcClient jdbc, DeadlineNormRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    public DeadlineNorm choose(String category, String type, String productCode,
                               Map<String, String> details, LocalDate registeredOn) {
        // сначала нормативы для конкретного продукта, затем общие для типа
        List<Mapping> mappings = jdbc.sql("""
                        select product_code, norm_code, priority from appeal_type_norm
                        where category = :category and type_code = :type
                          and (product_code is null or product_code = :product)
                        order by priority, product_code nulls last
                        """)
                .param("category", category)
                .param("type", type)
                .param("product", productCode)
                .query(Mapping.class)
                .list();

        for (Mapping mapping : mappings) {
            Optional<DeadlineNorm> norm = repository.findValid(mapping.normCode(), registeredOn)
                    .filter(found -> conditionHolds(found, details, registeredOn));
            if (norm.isPresent()) {
                return norm.get();
            }
        }
        return repository.findValid(GENERAL, registeredOn)
                .orElseThrow(() -> new IllegalStateException("Нет действующего общего норматива срока на " + registeredOn));
    }

    /**
     * Условия применения. 123-ФЗ, ст. 16 ч. 2 п. 1: срок 15 рабочих дней — если заявление
     * подано в электронной форме по стандартной форме и с нарушения прошло не более 180
     * дней. Форма претензии в кабинете повторяет стандартную форму Службы финансового
     * уполномоченного — в стенде это допущение.
     */
    private static boolean conditionHolds(DeadlineNorm norm, Map<String, String> details, LocalDate registeredOn) {
        if (norm.getCondition() == null) {
            return true;
        }
        if ("VIOLATION_WITHIN_180_DAYS".equals(norm.getCondition())) {
            try {
                LocalDate violation = LocalDate.parse(details.getOrDefault("violationDate", ""));
                return !violation.isBefore(registeredOn.minusDays(180));
            } catch (DateTimeParseException exception) {
                return false;
            }
        }
        throw new IllegalStateException("Неизвестное условие норматива: " + norm.getCondition());
    }
}
