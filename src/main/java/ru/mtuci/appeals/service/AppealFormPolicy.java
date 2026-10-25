package ru.mtuci.appeals.service;

import org.springframework.stereotype.Component;
import ru.mtuci.appeals.api.ApiModels.FormAppealType;
import ru.mtuci.appeals.api.ApiModels.FormCategory;
import ru.mtuci.appeals.api.ApiModels.FormField;
import ru.mtuci.appeals.api.ApiModels.FormSchemaResponse;
import ru.mtuci.appeals.api.ApiModels.ProductResponse;
import ru.mtuci.appeals.domain.ContractRole;
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
        return schema(productCode, null);
    }

    /**
     * clientRole: кем клиент приходится выбранному договору. Не страхователю не показываются
     * типы, которые оформляет только страхователь (решение 43); null, если роль не известна.
     */
    public FormSchemaResponse schema(String productCode, ContractRole clientRole) {
        Map<String, List<String>> documents = new LinkedHashMap<>();
        repository.documents().forEach(document -> documents
                .computeIfAbsent(document.categoryCode() + "/" + document.typeCode(), key -> new ArrayList<>())
                .add(document.label()));
        Map<String, FormCategory> categories = new LinkedHashMap<>();
        Map<String, FormAppealType> types = new LinkedHashMap<>();
        for (Row row : repository.rows(productCode)) {
            if (Boolean.TRUE.equals(row.policyholderOnly()) && clientRole != null && clientRole != ContractRole.POLICYHOLDER) {
                continue;
            }
            FormCategory category = categories.computeIfAbsent(row.categoryCode(),
                    code -> new FormCategory(code, row.categoryLabel(), new ArrayList<>()));
            FormAppealType type = types.computeIfAbsent(row.categoryCode() + "/" + row.typeCode(), key -> {
                FormAppealType created = new FormAppealType(row.typeCode(), row.typeLabel(), new ArrayList<>(),
                        documents.getOrDefault(key, List.of()), Boolean.TRUE.equals(row.policyholderOnly()));
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
    public Map<String, String> details(String category, String type, String productCode, ContractSnapshot contract,
                                       Map<String, String> submitted) {
        FormAppealType appealType = find(schema(productCode), category, type)
                .orElseThrow(() -> find(schema(null), category, type).isPresent()
                        ? new IllegalArgumentException("Тип обращения недоступен для договора «"
                                + (contract != null ? contract.getProductName() : productLabel(productCode)) + "»")
                        : new IllegalArgumentException("Недопустимый тип обращения для категории " + category));
        if (appealType.policyholderOnly() && contract != null && contract.getClientRole() != ContractRole.POLICYHOLDER) {
            throw new IllegalArgumentException("«" + appealType.label() + "» оформляет только страхователь, а по договору "
                    + contract.getPolicyNumber() + " клиент: " + roleLabel(contract.getClientRole()));
        }

        Map<String, String> details = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FormField field : appealType.fields()) {
            if (field.contractAttribute() != null && contract == null) {
                // договор ещё не привязан — поле заполнится при привязке (решение 29)
                continue;
            }
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

    /** Поля договора для типа обращения — когда договор привязывается позже регистрации. */
    public Map<String, String> contractDetails(String category, String type, ContractSnapshot contract) {
        Map<String, String> values = new LinkedHashMap<>();
        find(schema(null), category, type).ifPresent(appealType -> appealType.fields().stream()
                .filter(field -> field.contractAttribute() != null)
                .forEach(field -> {
                    String value = fromContract(field, contract);
                    if (value != null) {
                        values.put(field.code(), value);
                    }
                }));
        return values;
    }

    public boolean typeExists(String category, String type) {
        return find(schema(null), category, type).isPresent();
    }

    /** Виды страхования — для выбора, когда договоры клиента загрузить не удалось. */
    public List<ProductResponse> products() {
        return repository.products().stream()
                .map(product -> new ProductResponse(product.code(), product.label()))
                .toList();
    }

    public String productLabel(String code) {
        return repository.products().stream()
                .filter(product -> product.code().equals(code))
                .map(FormSchemaRepository.Product::label)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестный вид страхования: " + code));
    }

    /** Тип оформляет только страхователь: для проверки сотрудника, если договор привязали позже. */
    public boolean policyholderOnly(String category, String type) {
        return find(schema(null), category, type).map(FormAppealType::policyholderOnly).orElse(false);
    }

    public static String roleLabel(ContractRole role) {
        return role == null ? "участник с неизвестной ролью" : role.label();
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
