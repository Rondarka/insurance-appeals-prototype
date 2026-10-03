package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.domain.AppealCategory;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Серверная проверка: некорректное обращение отклоняется до сохранения, интерфейсу не доверяем. */
class AppealValidationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rejectsSubcategoryThatDoesNotBelongToCategory() {
        int before = appealsOfClient(CLIENT);
        CreateAppealRequest claimWithMortgageSubcategory = new CreateAppealRequest(
                CLIENT, CASCO_CONTRACT, AppealCategory.CLAIM, "RENEWAL",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "Москва"),
                "Продление", "Хочу продлить договор");

        ResponseEntity<ErrorResponse> response = post(claimWithMortgageSubcategory);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Недопустимый тип обращения");
        assertThat(appealsOfClient(CLIENT)).isEqualTo(before);
    }

    @Test
    void rejectsMissingRequiredDetails() {
        int before = appealsOfClient(CLIENT);
        CreateAppealRequest claimWithoutPlace = new CreateAppealRequest(
                CLIENT, CASCO_CONTRACT, AppealCategory.CLAIM, "AUTO",
                Map.of("incidentDate", "2026-09-10"),
                "ДТП", "Без места происшествия");

        ResponseEntity<ErrorResponse> response = post(claimWithoutPlace);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("incidentPlace");
        assertThat(appealsOfClient(CLIENT)).isEqualTo(before);
    }

    @Test
    void rejectsContractThatBelongsToAnotherClient() {
        UUID otherClient = UUID.fromString("19999999-9999-9999-9999-999999999999");
        UUID otherContract = UUID.fromString("29999999-9999-9999-9999-999999999999");
        jdbc.update("""
                insert into client (id, full_name, email, phone)
                values (?, 'Пётр Иванов', 'petr@example.ru', '+7 900 000-00-00')
                on conflict (id) do nothing""", otherClient);
        jdbc.update("""
                insert into insurance_contract
                    (id, client_id, policy_number, insurance_type, insured_object, valid_from, valid_to, status)
                values (?, ?, 'КАСКО-2026-999999', 'КАСКО', 'Автомобиль: Lada Vesta', ?, ?, 'ACTIVE')
                on conflict (id) do nothing""",
                otherContract, otherClient,
                Date.valueOf(LocalDate.of(2026, 1, 1)), Date.valueOf(LocalDate.of(2026, 12, 31)));

        int before = appealsOfClient(CLIENT);
        CreateAppealRequest strangersContract = new CreateAppealRequest(
                CLIENT, otherContract, AppealCategory.CLAIM, "AUTO",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "Москва"),
                "ДТП", "Обращение по чужому договору");

        ResponseEntity<ErrorResponse> response = post(strangersContract);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("не принадлежит");
        assertThat(appealsOfClient(CLIENT)).isEqualTo(before);
    }

    private ResponseEntity<ErrorResponse> post(CreateAppealRequest request) {
        return rest.postForEntity("/api/appeals", request, ErrorResponse.class);
    }
}
