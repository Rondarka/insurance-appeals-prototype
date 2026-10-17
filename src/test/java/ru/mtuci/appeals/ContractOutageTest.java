package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ChooseContractRequest;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.MeResponse;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.ContractLink;
import ru.mtuci.appeals.service.ContractLinkService;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Учётная система договоров недоступна — обращение всё равно принимается (решение 29).
 * Клиент указывает вид страхования, договор подсистема подбирает сама, когда система вернётся.
 */
class ContractOutageTest extends IntegrationTest {

    private static final UUID SECOND_CASCO = UUID.fromString("26666666-6666-6666-6666-666666666666");
    private static final Map<String, String> LOGIN_DETAILS =
            Map.of("systemSection", "Личный кабинет", "device", "Android, Chrome", "errorText", "Неверный код");
    private static final UUID PETR_CASCO = UUID.fromString("29999999-9999-9999-9999-999999999999");

    @Autowired
    private ContractLinkService linker;

    @AfterEach
    void sourceIsBack() {
        contractsSourceUp();
    }

    @Test
    void appealIsAcceptedAndRoutedWhileSourceIsDown() {
        contractsSourceDown();

        // кабинет открывается, договоров нет — клиент выбирает вид страхования
        MeResponse me = as(ANNA).getForObject("/api/me", MeResponse.class);
        assertThat(me.contractsUnavailable()).isTrue();
        assertThat(me.contracts()).isEmpty();

        AppealDetailResponse created = createAppeal(claim("CASCO", null, "2026-04-10"));
        assertThat(created.contractLink()).isEqualTo(ContractLink.PENDING);
        assertThat(created.contract()).isNull();
        assertThat(created.requestedProduct().label()).isEqualTo("КАСКО");

        // маршрутизация и срок по закону договора не ждут
        AppealDetailResponse routed = awaitRouted(created.id());
        assertThat(routed.status()).isEqualTo(AppealStatus.ROUTED);
        assertThat(routed.department().code()).isEqualTo("CLAIMS");
        assertThat(routed.legalDeadline()).isNotNull();

        // фоновая попытка, пока система лежит, ничего не меняет
        linker.linkPending();
        assertThat(getAppeal(created.id()).contractLink()).isEqualTo(ContractLink.PENDING);

        // система вернулась — сотрудник не ждёт следующей попытки
        contractsSourceUp();
        AppealDetailResponse linked = as(CLAIMS_SPECIALIST).postForObject(
                "/api/appeals/{id}/contract/link", null, AppealDetailResponse.class, created.id());

        // на дату происшествия действовал только первый договор КАСКО
        assertThat(linked.contractLink()).isEqualTo(ContractLink.LINKED);
        assertThat(linked.contract().policyNumber()).isEqualTo("КАСКО-2026-004821");
        // поле договора в атрибутах обращения заполнено при привязке
        assertThat(linked.details()).containsEntry("policyNumber", "КАСКО-2026-004821");
    }

    @Test
    void employeeLinkNowReportsThatSourceIsStillDown() {
        contractsSourceDown();
        UUID appeal = createAppeal(claim("CASCO", null, "2026-04-10")).id();
        awaitRouted(appeal);

        ResponseEntity<ErrorResponse> response = as(CLAIMS_SPECIALIST).postForEntity(
                "/api/appeals/{id}/contract/link", null, ErrorResponse.class, appeal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(getAppeal(appeal).contractLink()).isEqualTo(ContractLink.PENDING);
    }

    @Test
    void lastYearPolicyIsChosenByIncidentDate() {
        contractsSourceDown();
        UUID appeal = createAppeal(claim("OSAGO", null, "2026-02-20")).id();
        awaitRouted(appeal);
        contractsSourceUp();

        linker.linkPending();

        // ДТП до продления — убыток по прошлогоднему полису, а не по действующему
        assertThat(getAppeal(appeal).contract().policyNumber()).isEqualTo("ОСАГО-2025-118204");
    }

    @Test
    void clientChoosesWhenSeveralContractsFit() {
        contractsSourceDown();
        UUID appeal = createAppeal(loginQuestion("CASCO", null)).id();
        awaitRouted(appeal);
        contractsSourceUp();

        linker.linkPending();
        assertThat(getAppeal(appeal).contractLink()).isEqualTo(ContractLink.NEEDS_CHOICE);

        // два действующих КАСКО — выбор за клиентом, и только из его договоров
        ContractResponse[] candidates = as(ANNA).getForObject(
                "/api/appeals/{id}/contract-candidates", ContractResponse[].class, appeal);
        assertThat(candidates).extracting(ContractResponse::policyNumber)
                .containsExactlyInAnyOrder("КАСКО-2026-004821", "КАСКО-2026-007310");
        assertThat(as(PETR).getForEntity("/api/appeals/{id}/contract-candidates", String.class, appeal)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(ANNA).postForEntity("/api/appeals/{id}/contract",
                new ChooseContractRequest(PETR_CASCO), String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        AppealDetailResponse chosen = as(ANNA).postForObject("/api/appeals/{id}/contract",
                new ChooseContractRequest(SECOND_CASCO), AppealDetailResponse.class, appeal);

        assertThat(chosen.contractLink()).isEqualTo(ContractLink.LINKED);
        assertThat(chosen.contract().policyNumber()).isEqualTo("КАСКО-2026-007310");
    }

    @Test
    void policyNumberOrChosenContractNeedNoQuestions() {
        contractsSourceDown();
        // номер — в свободной форме; договор, выбранный до сбоя, — как есть
        UUID byNumber = createAppeal(loginQuestion("CASCO", "каско 2026 007310")).id();
        UUID byChosen = createAppeal(new CreateAppealRequest(CASCO_CONTRACT, "CASCO", null, "TECHNICAL", "LOGIN",
                LOGIN_DETAILS, "Не могу войти", "Код из SMS не принимается.")).id();
        // маршрутизация меняет обращение параллельно — ждём её, чтобы проход не отложил привязку
        awaitRouted(byNumber);
        awaitRouted(byChosen);
        contractsSourceUp();

        linker.linkPending();

        assertThat(getAppeal(byNumber).contract().policyNumber()).isEqualTo("КАСКО-2026-007310");
        assertThat(getAppeal(byChosen).contract().policyNumber()).isEqualTo("КАСКО-2026-004821");
    }

    @Test
    void contractOrProductIsRequired() {
        ResponseEntity<ErrorResponse> neither = as(ANNA).postForEntity("/api/appeals",
                new CreateAppealRequest(null, null, null, "TECHNICAL", "LOGIN",
                        LOGIN_DETAILS, "Не могу войти", "Код из SMS не принимается."),
                ErrorResponse.class);

        assertThat(neither.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(neither.getBody().message()).contains("вид страхования");
    }

    private static CreateAppealRequest claim(String product, String policyNumber, String incidentDate) {
        return new CreateAppealRequest(null, product, policyNumber, "CLAIM", "AUTO",
                Map.of("incidentDate", incidentDate, "incidentPlace", "г. Москва, Ленинградское шоссе, д. 39"),
                "ДТП на парковке", "Повреждён задний бампер, оформлен европротокол.");
    }

    private static CreateAppealRequest loginQuestion(String product, String policyNumber) {
        return new CreateAppealRequest(null, product, policyNumber, "TECHNICAL", "LOGIN",
                LOGIN_DETAILS, "Не могу войти", "Код из SMS не принимается.");
    }
}
