package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.mtuci.appeals.TestIdentityProvider.TestUser;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ErrorResponse;
import ru.mtuci.appeals.api.ApiModels.MessageResponse;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.domain.AuthorType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Файлы в переписке: сотрудник запрашивает документ, клиент досылает его ответом,
 * сотрудник может приложить к сообщению расчёт или письмо.
 */
class MessageAttachmentTest extends IntegrationTest {

    private static final byte[] PDF = "%PDF-1.4 справка управляющей компании".getBytes(StandardCharsets.UTF_8);

    @Test
    void clientSendsRequestedDocumentInReply() {
        UUID appeal = waitingForClient();

        AppealDetailResponse answered = as(ANNA).postForObject("/api/appeals/{id}/messages",
                message("Справку из управляющей компании прикладываю", "spravka-uk.pdf", "application/pdf", PDF),
                AppealDetailResponse.class, appeal);

        // ответ клиента возвращает обращение в работу, файл виден в его сообщении и в общем списке
        MessageResponse reply = last(answered);
        assertThat(answered.status()).isEqualTo(AppealStatus.IN_PROGRESS);
        assertThat(reply.authorType()).isEqualTo(AuthorType.CLIENT);
        assertThat(reply.attachments()).extracting(AttachmentResponse::originalName).containsExactly("spravka-uk.pdf");
        assertThat(answered.attachments()).extracting(AttachmentResponse::messageId).containsExactly(reply.id());

        // сотрудник отдела открывает именно этот файл
        UUID file = reply.attachments().get(0).id();
        ResponseEntity<byte[]> download = as(CLAIMS_SPECIALIST).getForEntity("/api/attachments/{id}", byte[].class, file);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo(PDF);
    }

    @Test
    void replyMayConsistOfFileOnly() {
        UUID appeal = waitingForClient();

        AppealDetailResponse answered = as(ANNA).postForObject("/api/appeals/{id}/messages",
                message(null, "foto-povrezhdeniy.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16}),
                AppealDetailResponse.class, appeal);

        assertThat(last(answered).body()).isEmpty();
        assertThat(last(answered).attachments()).hasSize(1);
        assertThat(answered.status()).isEqualTo(AppealStatus.IN_PROGRESS);
    }

    @Test
    void emptyReplyAndForbiddenFileTypeAreRejected() {
        UUID appeal = waitingForClient();
        int messagesBefore = getAppeal(appeal).messages().size();

        ResponseEntity<ErrorResponse> empty = as(ANNA).postForEntity("/api/appeals/{id}/messages",
                message("   ", null, null, null), ErrorResponse.class, appeal);
        ResponseEntity<ErrorResponse> wrongType = as(ANNA).postForEntity("/api/appeals/{id}/messages",
                message("Прикладываю файл", "script.txt", "text/plain", "echo".getBytes(StandardCharsets.UTF_8)),
                ErrorResponse.class, appeal);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // неподходящий файл не оставляет и сообщения без него, обращение по-прежнему ждёт клиента
        AppealDetailResponse after = getAppeal(appeal);
        assertThat(after.messages()).hasSize(messagesBefore);
        assertThat(after.status()).isEqualTo(AppealStatus.WAITING_CUSTOMER);
    }

    @Test
    void employeeAttachesDocumentForClient() {
        UUID appeal = waitingForClient();

        as(CLAIMS_SPECIALIST).postForObject("/api/appeals/{id}/messages",
                message("Направляем расчёт суммы выплаты", "raschet.pdf", "application/pdf", PDF),
                AppealDetailResponse.class, appeal);

        MessageResponse fromEmployee = last(getAppeal(appeal));
        assertThat(fromEmployee.authorType()).isEqualTo(AuthorType.EMPLOYEE);
        assertThat(as(ANNA).getForEntity("/api/attachments/{id}", byte[].class,
                fromEmployee.attachments().get(0).id()).getBody()).isEqualTo(PDF);
    }

    @Test
    void onlyParticipantsAttachFiles() {
        UUID appeal = waitingForClient();
        HttpEntity<MultiValueMap<String, Object>> file =
                message("Мой файл", "file.pdf", "application/pdf", PDF);

        // ни чужой клиент, ни сотрудник другого отдела обращения не видят
        assertThat(as(PETR).postForEntity("/api/appeals/{id}/messages", file, String.class, appeal)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(as(SUPPORT_SPECIALIST).postForEntity("/api/appeals/{id}/messages", file, String.class, appeal)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // к самому обращению файлы прикладывает только клиент, сотрудник прикладывает их к сообщению
        assertThat(as(CLAIMS_SPECIALIST).postForEntity("/api/appeals/{id}/attachments",
                files("file.pdf", PDF), String.class, appeal).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getAppeal(appeal).attachments()).isEmpty();
    }

    /** Страховой случай, по которому сотрудник запросил у клиента документы. */
    private UUID waitingForClient() {
        UUID id = createAppeal(cascoClaim()).id();
        awaitRouted(id);
        changeStatus(CLAIMS_SPECIALIST, id, AppealStatus.IN_PROGRESS);
        changeStatus(CLAIMS_SPECIALIST, id, AppealStatus.WAITING_CUSTOMER);
        return id;
    }

    private void changeStatus(TestUser employee, UUID appeal, AppealStatus status) {
        assertThat(as(employee).postForEntity("/api/appeals/{id}/status", new ChangeStatusRequest(status),
                String.class, appeal).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static MessageResponse last(AppealDetailResponse appeal) {
        return appeal.messages().get(appeal.messages().size() - 1);
    }

    /** Сообщение как его отправляет форма ответа: текст и файлы одним multipart-запросом. */
    private static HttpEntity<MultiValueMap<String, Object>> message(
            String body, String fileName, String contentType, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        if (body != null) {
            parts.add("body", body);
        }
        if (fileName != null) {
            parts.add("files", filePart(fileName, contentType, content));
        }
        return multipart(parts);
    }

    private static HttpEntity<MultiValueMap<String, Object>> files(String fileName, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("files", filePart(fileName, "application/pdf", content));
        return multipart(parts);
    }

    private static HttpEntity<ByteArrayResource> filePart(String fileName, String contentType, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDispositionFormData("files", fileName);
        return new HttpEntity<>(new ByteArrayResource(content), headers);
    }

    private static HttpEntity<MultiValueMap<String, Object>> multipart(MultiValueMap<String, Object> parts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }
}
