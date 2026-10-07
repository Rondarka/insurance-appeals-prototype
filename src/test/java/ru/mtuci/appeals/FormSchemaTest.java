package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.FormAppealType;
import ru.mtuci.appeals.api.ApiModels.FormCategory;
import ru.mtuci.appeals.api.ApiModels.FormField;
import ru.mtuci.appeals.api.ApiModels.FormSchemaResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Схема формы — справочник подсистемы (решение 24): интерфейс строит форму по схеме,
 * сервер проверяет обращение по ней же.
 */
class FormSchemaTest extends IntegrationTest {

    @Test
    void schemaForProductContainsOnlyItsTypes() {
        FormSchemaResponse schema = schema("CASCO");

        // клиент с КАСКО не видит ипотечных типов и страховых случаев других продуктов
        assertThat(schema.categories()).extracting(FormCategory::code)
                .doesNotContain("MORTGAGE")
                .contains("CLAIM", "POLICY", "PAYMENT", "TECHNICAL");
        assertThat(category(schema, "CLAIM").types()).extracting(FormAppealType::code)
                .containsExactly("AUTO");
        assertThat(type(schema, "CLAIM", "AUTO").fields())
                .extracting(FormField::code, FormField::inputType, FormField::required, FormField::contractAttribute)
                .containsExactly(
                        tuple("policyNumber", "TEXT", true, "policyNumber"),
                        tuple("incidentDate", "DATE", true, null),
                        tuple("incidentPlace", "TEXT", true, null));
    }

    @Test
    void schemaWithoutProductListsAllTypes() {
        FormSchemaResponse schema = schema(null);

        assertThat(schema.categories()).extracting(FormCategory::code)
                .containsExactly("MORTGAGE", "CLAIM", "POLICY", "PAYMENT", "COMPLAINT", "TECHNICAL", "OTHER");
        assertThat(category(schema, "CLAIM").types()).extracting(FormAppealType::code)
                .containsExactly("AUTO", "PROPERTY", "HEALTH");
        assertThat(type(schema, "OTHER", "GENERAL").fields()).isEmpty();
    }

    @Test
    void rejectsTypeNotAvailableForContractProduct() {
        int before = appealsOf(ANNA);
        CreateAppealRequest mortgageTypeOnCasco = new CreateAppealRequest(
                CASCO_CONTRACT, "MORTGAGE", "DOCUMENTS", Map.of(),
                "Документы", "Нужны документы по договору");

        ResponseEntity<ErrorResponse> response = post(mortgageTypeOnCasco);

        // тип существует, но продукт договора его не допускает
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("недоступен для договора «КАСКО»");
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    @Test
    void rejectsValueOfWrongFormat() {
        int before = appealsOf(ANNA);
        CreateAppealRequest claimWithRussianDate = new CreateAppealRequest(
                CASCO_CONTRACT, "CLAIM", "AUTO",
                Map.of("incidentDate", "10.09.2026", "incidentPlace", "Москва"),
                "ДТП", "Дата не в формате ISO");

        ResponseEntity<ErrorResponse> response = post(claimWithRussianDate);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Дата происшествия");
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    @Test
    void contractFieldsComeFromContractAndUnknownFieldsAreDropped() {
        CreateAppealRequest request = new CreateAppealRequest(
                CASCO_CONTRACT, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "Москва",
                        "policyNumber", "ПОДДЕЛЬНЫЙ-001", "bonus", "выплатить вдвое"),
                "ДТП", "Попытка подменить номер полиса");

        AppealDetailResponse appeal = createAppeal(request);

        assertThat(appeal.details())
                .containsEntry("policyNumber", "КАСКО-2026-004821")
                .containsOnlyKeys("policyNumber", "incidentDate", "incidentPlace");
    }

    private FormSchemaResponse schema(String productCode) {
        String url = productCode == null ? "/api/form-schema" : "/api/form-schema?productCode=" + productCode;
        return as(ANNA).getForObject(url, FormSchemaResponse.class);
    }

    private static FormCategory category(FormSchemaResponse schema, String code) {
        return schema.categories().stream()
                .filter(item -> item.code().equals(code))
                .findFirst().orElseThrow();
    }

    private static FormAppealType type(FormSchemaResponse schema, String category, String code) {
        return category(schema, category).types().stream()
                .filter(item -> item.code().equals(code))
                .findFirst().orElseThrow();
    }

    private ResponseEntity<ErrorResponse> post(CreateAppealRequest request) {
        return as(ANNA).postForEntity("/api/appeals", request, ErrorResponse.class);
    }
}
