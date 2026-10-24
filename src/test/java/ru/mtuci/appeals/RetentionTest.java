package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.MessageResponse;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.api.ApiModels.RetentionRequest;
import ru.mtuci.appeals.api.ApiModels.RetentionResponse;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.service.RetentionService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сроки хранения (решение 41): у решённого обращения с истёкшим сроком персональные данные
 * стираются, а тип, отдел, даты и итог остаются для аналитики. Открытое обращение не
 * трогается. Срок меняет только администратор, в разумных пределах и с журналом.
 */
class RetentionTest extends IntegrationTest {

    @Autowired
    private RetentionService retention;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void personalDataIsErasedButFactsRemain() {
        UUID resolved = routed();
        UUID file = as(ANNA).postForEntity("/api/appeals/{id}/attachments", pdf(), AttachmentResponse[].class, resolved)
                .getBody()[0].id();
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), String.class, resolved).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/resolve", new ResolveAppealRequest(
                AppealOutcome.EXPLAINED, "Анна, код приходил с задержкой, отправку SMS исправили.", null, null),
                String.class, resolved).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID open = routed();

        assertThat(retention.anonymizeResolvedBefore(Instant.now().plusSeconds(60))).isPositive();

        AppealDetailResponse erased = as(ADMIN).getForObject("/api/appeals/{id}", AppealDetailResponse.class, resolved);
        assertThat(erased.customerName()).isEqualTo("Клиент обезличен");
        assertThat(erased.customerEmail()).isEmpty();
        assertThat(erased.description()).isEqualTo("Удалено по сроку хранения");
        assertThat(erased.details()).isEmpty();
        assertThat(erased.messages()).extracting(MessageResponse::body).containsOnly("Удалено по сроку хранения");
        assertThat(erased.attachments()).isEmpty();
        assertThat(erased.history()).noneMatch(item -> item.actor().contains("Анна") || item.description().contains("Анна"));
        // для аналитики остаётся всё, в чём нет персональных данных
        assertThat(erased.category()).isEqualTo("TECHNICAL");
        assertThat(erased.outcome()).isEqualTo(AppealOutcome.EXPLAINED);
        assertThat(erased.department().code()).isEqualTo("SUPPORT");
        assertThat(jdbc.sql("select count(*) from appeal_facts where id = :id").param("id", resolved)
                .query(Long.class).single()).isEqualTo(1);
        // связь с клиентом разорвана: в кабинете его больше нет, файл удалён
        assertThat(Arrays.stream(as(ANNA).getForObject("/api/appeals", AppealSummaryResponse[].class)))
                .extracting(AppealSummaryResponse::id).doesNotContain(resolved).contains(open);
        assertThat(as(ADMIN).getForEntity("/api/attachments/{id}", String.class, file).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // открытое обращение не тронуто
        assertThat(getAppeal(open).customerName()).isEqualTo("Анна Смирнова");
    }

    @Test
    void retentionPeriodIsChangedByAdministratorWithinLimits() {
        assertThat(as(ADMIN).exchange("/api/admin/retention", HttpMethod.PUT,
                new HttpEntity<>(new RetentionRequest(0, "Проверка нижней границы")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(as(SUPPORT_SUPERVISOR).exchange("/api/admin/retention", HttpMethod.PUT,
                new HttpEntity<>(new RetentionRequest(3, "Не администратор")), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        RetentionResponse saved = as(ADMIN).exchange("/api/admin/retention", HttpMethod.PUT,
                new HttpEntity<>(new RetentionRequest(7, "Новая номенклатура дел")), RetentionResponse.class).getBody();
        assertThat(saved.years()).isEqualTo(7);
        assertThat(jdbc.sql("select count(*) from settings_audit where entity = 'RETENTION'").query(Long.class).single())
                .isPositive();
        as(ADMIN).exchange("/api/admin/retention", HttpMethod.PUT,
                new HttpEntity<>(new RetentionRequest(5, "Вернули прежний срок")), RetentionResponse.class);
    }

    private UUID routed() {
        UUID id = createAppeal(loginProblem()).id();
        awaitRouted(id);
        return id;
    }

    private static HttpEntity<MultiValueMap<String, Object>> pdf() {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        partHeaders.setContentDispositionFormData("files", "skrin.pdf");
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("files", new HttpEntity<>(new ByteArrayResource("%PDF-1.4 снимок".getBytes(StandardCharsets.UTF_8)), partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }
}
