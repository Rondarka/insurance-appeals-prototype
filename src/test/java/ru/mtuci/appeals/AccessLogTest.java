package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.mtuci.appeals.api.ApiModels.AccessLogResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Журнал доступа (решение 40): кто из сотрудников открывал обращение и его файлы. Повторный
 * просмотр того же сотрудника подряд отдельной записью не пишется, клиент в журнал не
 * попадает, смотреть журнал может только администратор.
 */
class AccessLogTest extends IntegrationTest {

    @Test
    void employeeViewsAndOpenedFilesAreLoggedClientsAreNot() {
        UUID appeal = createAppeal(loginProblem()).id();
        awaitRouted(appeal);
        UUID file = as(ANNA).postForEntity("/api/appeals/{id}/attachments",
                pdf("skrin-oshibki.pdf"), AttachmentResponse[].class, appeal).getBody()[0].id();

        getAppeal(appeal);
        as(SUPPORT_SPECIALIST).getForObject("/api/appeals/{id}", String.class, appeal);
        // карточка обновляется каждые 10 секунд: повтор подряд не плодит записей
        as(SUPPORT_SPECIALIST).getForObject("/api/appeals/{id}", String.class, appeal);
        as(SUPPORT_SPECIALIST).getForObject("/api/attachments/{id}", byte[].class, file);

        List<AccessLogResponse> log = log(appeal);
        assertThat(log).filteredOn(entry -> entry.action().equals("VIEWED"))
                .extracting(AccessLogResponse::viewerName).containsExactly("Елена Соколова");
        AccessLogResponse opened = log.stream().filter(entry -> entry.action().equals("FILE_OPENED")).findFirst().orElseThrow();
        assertThat(opened.file()).isEqualTo("skrin-oshibki.pdf");
        assertThat(opened.department()).isEqualTo("Техническая поддержка");
        assertThat(log).extracting(AccessLogResponse::viewerName).doesNotContain("Анна Смирнова");
    }

    @Test
    void onlyAdministratorSeesTheLog() {
        UUID appeal = createAppeal(loginProblem()).id();
        assertThat(as(SUPPORT_SUPERVISOR).getForEntity("/api/admin/appeals/{id}/access-log", String.class, appeal)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private List<AccessLogResponse> log(UUID appeal) {
        return List.of(as(ADMIN).getForObject("/api/admin/appeals/{id}/access-log", AccessLogResponse[].class, appeal));
    }

    private static HttpEntity<MultiValueMap<String, Object>> pdf(String name) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        partHeaders.setContentDispositionFormData("files", name);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("files", new HttpEntity<>(new ByteArrayResource("%PDF-1.4 снимок экрана".getBytes(StandardCharsets.UTF_8)),
                partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }
}
