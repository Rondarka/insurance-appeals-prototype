package ru.mtuci.appeals.service;

import org.springframework.stereotype.Component;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.domain.AppealCategory;

import java.util.List;
import java.util.Map;

@Component
public class AppealFormPolicy {

    private static final Map<AppealCategory, Map<String, List<String>>> REQUIRED_FIELDS = Map.of(
            AppealCategory.MORTGAGE, Map.of(
                    "DOCUMENTS", List.of("policyNumber", "objectAddress"),
                    "RENEWAL", List.of("policyNumber", "objectAddress"),
                    "PAYMENT", List.of("policyNumber", "paymentDate", "paymentAmount"),
                    "CLAIM_EVENT", List.of("policyNumber", "incidentDate", "objectAddress"),
                    "DATA_CHANGE", List.of("policyNumber", "objectAddress")
            ),
            AppealCategory.CLAIM, Map.of(
                    "AUTO", List.of("policyNumber", "incidentDate", "incidentPlace"),
                    "PROPERTY", List.of("policyNumber", "incidentDate", "incidentPlace"),
                    "HEALTH", List.of("policyNumber", "incidentDate", "incidentPlace")
            ),
            AppealCategory.POLICY, Map.of(
                    "COPY", List.of("policyNumber"),
                    "DATA_CHANGE", List.of("policyNumber"),
                    "TERMINATION", List.of("policyNumber")
            ),
            AppealCategory.PAYMENT, Map.of(
                    "PAYMENT_STATUS", List.of("policyNumber", "paymentDate", "paymentAmount"),
                    "REFUND", List.of("policyNumber", "paymentDate", "paymentAmount"),
                    "INCORRECT_AMOUNT", List.of("policyNumber", "paymentDate", "paymentAmount")
            ),
            AppealCategory.COMPLAINT, Map.of(
                    "SERVICE_QUALITY", List.of("desiredOutcome"),
                    "DEADLINE", List.of("relatedAppealNumber", "desiredOutcome"),
                    "EMPLOYEE", List.of("desiredOutcome")
            ),
            AppealCategory.TECHNICAL, Map.of(
                    "LOGIN", List.of("systemSection", "device", "errorText"),
                    "PERSONAL_ACCOUNT", List.of("systemSection", "device", "errorText"),
                    "DOCUMENT_UPLOAD", List.of("systemSection", "device", "errorText")
            ),
            AppealCategory.OTHER, Map.of(
                    "GENERAL", List.of()
            )
    );

    public void validate(CreateAppealRequest request, Map<String, String> details) {
        Map<String, List<String>> categoryRules = REQUIRED_FIELDS.get(request.category());
        if (categoryRules == null || !categoryRules.containsKey(request.subcategory())) {
            throw new IllegalArgumentException(
                    "Недопустимый тип обращения для категории " + request.category());
        }

        List<String> missing = categoryRules.get(request.subcategory()).stream()
                .filter(key -> isBlank(details.get(key)))
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не заполнены обязательные поля: " + String.join(", ", missing));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
