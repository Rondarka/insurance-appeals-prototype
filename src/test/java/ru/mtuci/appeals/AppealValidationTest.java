package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Серверная проверка формы: некорректное обращение отклоняется до сохранения, интерфейсу
 * не доверяем. Проверки договора — в {@link ContractIntegrationTest}.
 */
class AppealValidationTest extends IntegrationTest {

    @Test
    void rejectsSubcategoryThatDoesNotBelongToCategory() {
        int before = appealsOf(ANNA);
        CreateAppealRequest claimWithMortgageSubcategory = new CreateAppealRequest(
                CASCO_CONTRACT, "CLAIM", "RENEWAL",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "Москва"),
                "Продление", "Хочу продлить договор");

        ResponseEntity<ErrorResponse> response = post(claimWithMortgageSubcategory);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Недопустимый тип обращения");
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    @Test
    void rejectsMissingRequiredDetails() {
        int before = appealsOf(ANNA);
        CreateAppealRequest claimWithoutPlace = new CreateAppealRequest(
                CASCO_CONTRACT, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-09-10"),
                "ДТП", "Без места происшествия");

        ResponseEntity<ErrorResponse> response = post(claimWithoutPlace);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Место происшествия");
        assertThat(appealsOf(ANNA)).isEqualTo(before);
    }

    private ResponseEntity<ErrorResponse> post(CreateAppealRequest request) {
        return as(ANNA).postForEntity("/api/appeals", request, ErrorResponse.class);
    }
}
