package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.domain.AppealStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Подсистема как Resource Server (решение 22): принимает только токен поставщика
 * идентификации, выпущенный для неё, и берёт из него, кто делает запрос.
 */
class SecurityTest extends IntegrationTest {

    private static final UUID PETR_CASCO_CONTRACT = UUID.fromString("29999999-9999-9999-9999-999999999999");

    @Test
    void requestWithoutTokenIsRejected() {
        assertThat(rest.getForEntity("/api/appeals", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenIssuedForAnotherServiceIsRejected() {
        String foreign = TestIdentityProvider.tokenForAudience(ANNA, "another-service");

        assertThat(withToken(foreign).getForEntity("/api/appeals", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void forgedTokenIsRejected() {
        String forged = TestIdentityProvider.forgedToken(ANNA);

        assertThat(withToken(forged).getForEntity("/api/appeals", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void appealIsRegisteredToClientFromToken() {
        AppealDetailResponse created = as(PETR).postForEntity("/api/appeals", petrClaim(),
                AppealDetailResponse.class).getBody();

        // имя — из токена: таблицы клиентов в подсистеме нет
        assertThat(created.customerName()).isEqualTo("Пётр Иванов");
        assertThat(as(PETR).getForObject("/api/appeals", AppealSummaryResponse[].class))
                .extracting(AppealSummaryResponse::id).contains(created.id());
        assertThat(as(ANNA).getForObject("/api/appeals", AppealSummaryResponse[].class))
                .extracting(AppealSummaryResponse::id).doesNotContain(created.id());
    }

    @Test
    void clientDoesNotSeeAnotherClientsAppeal() {
        UUID annasAppeal = createAppeal(cascoClaim()).id();

        ResponseEntity<ErrorResponse> response = as(PETR).getForEntity(
                "/api/appeals/{id}", ErrorResponse.class, annasAppeal);

        // как будто обращения нет: не раскрываем, что оно существует
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyEmployeeOfAppealDepartmentWorksWithIt() {
        UUID appeal = createAppeal(cascoClaim()).id();
        awaitRouted(appeal);

        // обращение в урегулировании убытков: сотрудник поддержки его даже не видит
        ResponseEntity<ErrorResponse> bySupport = changeStatus(SUPPORT_SPECIALIST, appeal, ErrorResponse.class);
        ResponseEntity<AppealDetailResponse> byClaims = changeStatus(CLAIMS_SPECIALIST, appeal,
                AppealDetailResponse.class);

        assertThat(bySupport.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(byClaims.getStatusCode()).isEqualTo(HttpStatus.OK);
        // исполнитель — из токена, а не из тела запроса
        assertThat(byClaims.getBody().assignedEmployee()).isEqualTo("Сергей Лебедев");
    }

    @Test
    void clientCannotPerformEmployeeOperations() {
        UUID appeal = createAppeal(cascoClaim()).id();
        awaitRouted(appeal);

        assertThat(changeStatus(ANNA, appeal, ErrorResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private <T> ResponseEntity<T> changeStatus(TestIdentityProvider.TestUser user, UUID appeal, Class<T> type) {
        return as(user).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), type, appeal);
    }

    private static CreateAppealRequest petrClaim() {
        return new CreateAppealRequest(
                PETR_CASCO_CONTRACT, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-09-20", "incidentPlace", "г. Москва"),
                "Повреждение на парковке", "Обнаружил вмятину на двери.");
    }
}
