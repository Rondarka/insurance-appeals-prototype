package ru.mtuci.appeals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.mtuci.appeals.api.ApiModels.AddMessageRequest;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AssignRequest;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.MeResponse;
import ru.mtuci.appeals.api.ApiModels.StaffMemberResponse;
import ru.mtuci.appeals.domain.AppealStatus;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Исполнитель обращения (решение 32): руководитель назначает сотрудника своего отдела,
 * «мои» определяются по идентификатору в IdP; в очереди видно, где клиент ответил.
 */
class AssignmentTest extends IntegrationTest {

    /** Сотрудники открыли рабочее место — подсистема знает их по токенам. */
    @BeforeEach
    void staffLoggedIn() {
        for (var user : new TestIdentityProvider.TestUser[]{CLAIMS_SPECIALIST, CLAIMS_SUPERVISOR, SUPPORT_SPECIALIST}) {
            as(user).getForObject("/api/me", MeResponse.class);
        }
    }

    @Test
    void supervisorAssignsSpecialistOfOwnDepartment() {
        UUID appeal = routedClaim();

        StaffMemberResponse[] staff = as(CLAIMS_SUPERVISOR).getForObject("/api/staff", StaffMemberResponse[].class);
        assertThat(staff).extracting(StaffMemberResponse::id)
                .contains(CLAIMS_SPECIALIST.subject(), CLAIMS_SUPERVISOR.subject())
                .doesNotContain(SUPPORT_SPECIALIST.subject());

        AppealDetailResponse assigned = as(CLAIMS_SUPERVISOR).postForObject("/api/appeals/{id}/assign",
                new AssignRequest(CLAIMS_SPECIALIST.subject()), AppealDetailResponse.class, appeal);

        assertThat(assigned.assignedSubject()).isEqualTo(CLAIMS_SPECIALIST.subject());
        assertThat(assigned.assignedEmployee()).isEqualTo(CLAIMS_SPECIALIST.name());
        assertThat(summaryOf(CLAIMS_SPECIALIST, appeal).assignedSubject()).isEqualTo(CLAIMS_SPECIALIST.subject());
    }

    @Test
    void onlySupervisorAssignsAndOnlyWithinDepartment() {
        UUID appeal = routedClaim();

        // специалист назначать не может, сотрудника чужого отдела назначить нельзя
        assertThat(as(CLAIMS_SPECIALIST).postForEntity("/api/appeals/{id}/assign",
                new AssignRequest(CLAIMS_SPECIALIST.subject()), String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as(CLAIMS_SUPERVISOR).postForEntity("/api/appeals/{id}/assign",
                new AssignRequest(SUPPORT_SPECIALIST.subject()), String.class, appeal).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAppeal(appeal).assignedSubject()).isNull();
    }

    @Test
    void takingIntoWorkAssignsYourself() {
        UUID appeal = routedClaim();

        as(CLAIMS_SPECIALIST).postForObject("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), AppealDetailResponse.class, appeal);

        assertThat(getAppeal(appeal).assignedSubject()).isEqualTo(CLAIMS_SPECIALIST.subject());
    }

    @Test
    void queueShowsWhereClientReplied() {
        UUID appeal = routedClaim();
        assertThat(summaryOf(CLAIMS_SPECIALIST, appeal).clientReplied()).isFalse();

        // клиент дописал — ход за сотрудником
        as(ANNA).postForObject("/api/appeals/{id}/messages", new AddMessageRequest("Прикладываю фото повреждений"),
                AppealDetailResponse.class, appeal);
        assertThat(summaryOf(CLAIMS_SPECIALIST, appeal).clientReplied()).isTrue();

        // сотрудник ответил — отметка снимается
        as(CLAIMS_SPECIALIST).postForObject("/api/appeals/{id}/messages", new AddMessageRequest("Спасибо, фото получили"),
                AppealDetailResponse.class, appeal);
        assertThat(summaryOf(CLAIMS_SPECIALIST, appeal).clientReplied()).isFalse();
    }

    private UUID routedClaim() {
        UUID id = createAppeal(cascoClaim()).id();
        awaitRouted(id);
        return id;
    }

    private AppealSummaryResponse summaryOf(TestIdentityProvider.TestUser user, UUID appeal) {
        return Arrays.stream(as(user).getForObject("/api/appeals", AppealSummaryResponse[].class))
                .filter(item -> item.id().equals(appeal))
                .findFirst()
                .orElseThrow();
    }
}
