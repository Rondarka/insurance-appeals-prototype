package ru.mtuci.appeals.service;

import org.springframework.stereotype.Component;
import ru.mtuci.appeals.api.ApiModels.FormAppealType;
import ru.mtuci.appeals.api.ApiModels.FormCategory;
import ru.mtuci.appeals.api.ApiModels.FormField;
import ru.mtuci.appeals.api.ApiModels.FormSchemaResponse;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.repository.FormSchemaRepository;
import ru.mtuci.appeals.repository.FormSchemaRepository.Row;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Схема формы обращения (решение 24): один источник правды и для интерфейса, и для
 * серверной проверки. Правила хранятся в справочнике, а не в коде.
 */
@Component
public class AppealFormPolicy {

    /** Атрибуты договора, которыми сервер заполняет поля. Набор задан контрактом интеграции. */
    private static final Map<String, Function<ContractSnapshot, String>> CONTRACT_ATTRIBUTES = Map.of(
            "policyNumber", ContractSnapshot::getPolicyNumber,
            "insuredObject", ContractSnapshot::getInsuredObject
    );

    private final FormSchemaRepository repository;

    public AppealFormPolicy(FormSchemaRepository repository) {
        this.repository = repository;
    }

    /** productCode == null — полная схема, например для подписей в рабочем месте сотрудника. */
    public FormSchemaResponse schema(String productCode) {
        Map<String, FormCategory> categories = new LinkedHashMap<>();
        Map<String, FormAppealType> types = new LinkedHashMap<>();
        for (Row row : repository.rows(productCode)) {
            FormCategory category = categories.computeIfAbsent(row.categoryCode(),
                    code -> new FormCategory(code, row.categoryLabel(), new ArrayList<>()));
            FormAppealType type = types.computeIfAbsent(row.categoryCode() + "/" + row.typeCode(), key -> {
                FormAppealType created = new FormAppealType(row.typeCode(), row.typeLabel(), new ArrayList<>());
                category.types().add(created);
                return created;
            });
            if (row.fieldCode() != null) {
                type.fields().add(new FormField(row.fieldCode(), row.fieldLabel(), row.inputType(),
                        row.hint(), row.required(), row.contractAttribute()));
            }
        }
        return new FormSchemaResponse(List.copyOf(categories.values()));
    }

    /**
     * Проверяет обращение по схеме и возвращает его атрибуты. Поля договора подставляет
     * сервер — клиент не может их подменить; поля, которых нет в схеме типа, отбрасываются.
     */
    public Map<String, String> details(String category, String type, ContractSnapshot contract,
                                       Map<String, String> submitted) {
        FormAppealType appealType = find(schema(contract.getProductCode()), category, type)
                .orElseThrow(() -> find(schema(null), category, type).isPresent()
                        ? new IllegalArgumentException("Тип обращения недоступен для договора «"
                                + contract.getProductName() + "»")
                        : new IllegalArgumentException("Недопустимый тип обращения для категории " + category));

        Map<String, String> details = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FormField field : appealType.fields()) {
            String value = field.contractAttribute() != null
                    ? fromContract(field, contract)
                    : trimmed(submitted.get(field.code()));
            if (value == null) {
                if (field.required()) {
                    missing.add(field.label());
                }
                continue;
            }
            checkFormat(field, value);
            details.put(field.code(), value);
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Не заполнены обязательные поля: " + String.join(", ", missing));
        }
        return details;
    }

    private static Optional<FormAppealType> find(FormSchemaResponse schema, String category, String type) {
        return schema.categories().stream()
                .filter(item -> item.code().equals(category))
                .flatMap(item -> item.types().stream())
                .filter(item -> item.code().equals(type))
                .findFirst();
    }

    private static String fromContract(FormField field, ContractSnapshot contract) {
        Function<ContractSnapshot, String> attribute = CONTRACT_ATTRIBUTES.get(field.contractAttribute());
        if (attribute == null) {
            throw new IllegalStateException("Неизвестный атрибут договора в схеме формы: " + field.contractAttribute());
        }
        return trimmed(attribute.apply(contract));
    }

    private static void checkFormat(FormField field, String value) {
        try {
            switch (field.inputType()) {
                case "DATE" -> LocalDate.parse(value);
                case "NUMBER" -> new BigDecimal(value);
                default -> {
                }
            }
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw new IllegalArgumentException("Поле «" + field.label() + "» заполнено неверно: ожидается "
                    + ("DATE".equals(field.inputType()) ? "дата в формате ГГГГ-ММ-ДД" : "число"));
        }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
