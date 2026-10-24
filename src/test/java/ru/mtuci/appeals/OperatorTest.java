package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AnalyticsResponse;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.HistoryResponse;
import ru.mtuci.appeals.api.ApiModels.OperatorAppealRequest;
import ru.mtuci.appeals.api.ApiModels.OperatorClientResponse;
import ru.mtuci.appeals.api.ApiModels.ShareItem;
import ru.mtuci.appeals.domain.AppealChannel;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Оператор контакт-центра (решение 38): находит клиента по номеру договора и регистрирует
 * обращение с его слов. Дальше обращение живёт как поданное из кабинета: клиент видит его у
 * себя, срок по закону и маршрутизация те же.
 */
class OperatorTest extends IntegrationTest {

    private static final String ANNA_CASCO = "КАСКО-2026-004821";
    private static final UUID PETR_CONTRACT = UUID.fromString("29999999-9999-9999-9999-999999999999");

    @Test
    void operatorFindsClientByPolicyNumberAndRegistersAppealFromPhoneCall() {
        AnalyticsResponse before = analytics();
        OperatorClientResponse client = as(OPERATOR).getForObject("/api/operator/clients?policyNumber={number}",
                OperatorClientResponse.class, ANNA_CASCO);
        assertThat(client.counterpartyId()).isEqualTo(ANNA_COUNTERPARTY);
        assertThat(client.contracts()).extracting(ContractResponse::policyNumber).contains(ANNA_CASCO);

        ResponseEntity<AppealDetailResponse> response = as(OPERATOR).postForEntity("/api/operator/appeals",
                new OperatorAppealRequest(ANNA_COUNTERPARTY, AppealChannel.PHONE, "Анна Сергеевна", loginProblem()),
                AppealDetailResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AppealDetailResponse created = response.getBody();
        assertThat(created.channel()).isEqualTo(AppealChannel.PHONE);
        assertThat(created.registeredBy()).isEqualTo("Наталья Ковалёва");
        assertThat(created.customerName()).isEqualTo("Анна Сергеевна");
        assertThat(created.legalDeadline()).isNotNull();
        assertThat(created.history()).extracting(HistoryResponse::description)
                .anyMatch(text -> text.contains("со слов клиента по телефону"));

        // дальше как обычное обращение: маршрутизация, клиент видит его в кабинете
        awaitRouted(created.id());
        assertThat(Arrays.stream(as(ANNA).getForObject("/api/appeals", AppealSummaryResponse[].class)))
                .extracting(AppealSummaryResponse::id).contains(created.id());
        // и оператор видит его у клиента — на случай звонка «что с моим обращением»
        assertThat(as(OPERATOR).getForObject("/api/operator/clients?policyNumber={number}",
                OperatorClientResponse.class, ANNA_CASCO).appeals())
                .extracting(AppealSummaryResponse::id).contains(created.id());

        AnalyticsResponse after = analytics();
        assertThat(phone(after) - phone(before)).isEqualTo(1);
    }

    @Test
    void contractOfAnotherClientAndUnknownPolicyAreRejected() {
        CreateAppealRequest foreign = new CreateAppealRequest(PETR_CONTRACT, null, null, "TECHNICAL", "LOGIN",
                loginProblem().details(), "Не могу войти", "Код не приходит.");
        assertThat(as(OPERATOR).postForEntity("/api/operator/appeals",
                new OperatorAppealRequest(ANNA_COUNTERPARTY, AppealChannel.OFFICE, "Анна", foreign), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(as(OPERATOR).getForEntity("/api/operator/clients?policyNumber=НЕТ-0000", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // канал «кабинет» — это сам клиент, не оператор
        assertThat(as(OPERATOR).postForEntity("/api/operator/appeals",
                new OperatorAppealRequest(ANNA_COUNTERPARTY, AppealChannel.CABINET, "Анна", loginProblem()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyOperatorRegistersOnBehalfOfClient() {
        assertThat(as(ANNA).getForEntity("/api/operator/clients?policyNumber={number}", String.class, ANNA_CASCO)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/operator/appeals",
                new OperatorAppealRequest(ANNA_COUNTERPARTY, AppealChannel.PHONE, "Анна", loginProblem()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // свою форму клиента оператор не использует: у него нет идентификатора клиента в токене
        assertThat(as(OPERATOR).postForEntity("/api/appeals", loginProblem(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private AnalyticsResponse analytics() {
        return as(ADMIN).getForObject("/api/admin/analytics?days=30", AnalyticsResponse.class);
    }

    private static long phone(AnalyticsResponse analytics) {
        return analytics.channels().stream().filter(item -> item.code().equals("PHONE"))
                .mapToLong(item -> item.rate().count()).sum();
    }
}
