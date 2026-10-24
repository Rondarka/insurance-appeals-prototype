package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AttachmentResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка вложений (решение 39): тип определяется по содержимому, а не по имени и не по
 * слову браузера; заражённый файл не принимается; если антивирус недоступен, непроверенный
 * файл тоже не принимается.
 */
class AttachmentCheckTest extends IntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

    @AfterEach
    void antivirusBack() {
        TestAntivirus.available(true);
    }

    @Test
    void typeComesFromContentNotFromName() {
        UUID appeal = createAppeal(cascoClaim()).id();

        // страница, переименованная в фото, не пройдёт
        ResponseEntity<String> disguised = upload(appeal, "photo.png", "image/png",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertThat(disguised.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(disguised.getBody()).contains("PDF, JPG и PNG");

        // настоящий PNG проходит, даже если браузер не знает его типа, и хранится как PNG
        ResponseEntity<AttachmentResponse[]> png = as(ANNA).postForEntity("/api/appeals/{id}/attachments",
                files("scan", "application/octet-stream", PNG), AttachmentResponse[].class, appeal);
        assertThat(png.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(png.getBody()[0].contentType()).isEqualTo("image/png");
    }

    @Test
    void infectedFileIsRejectedAndUncheckedFileToo() {
        UUID appeal = createAppeal(cascoClaim()).id();
        byte[] infected = ("%PDF-1.4\n" + TestAntivirus.EICAR).getBytes(StandardCharsets.ISO_8859_1);

        ResponseEntity<String> virus = upload(appeal, "akt.pdf", "application/pdf", infected);
        assertThat(virus.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(virus.getBody()).contains("антивирусную проверку");

        TestAntivirus.available(false);
        ResponseEntity<String> unchecked = upload(appeal, "akt.pdf", "application/pdf",
                "%PDF-1.4 акт осмотра".getBytes(StandardCharsets.UTF_8));
        assertThat(unchecked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(as(ANNA).getForObject("/api/appeals/{id}", AppealDetailResponse.class, appeal).attachments()).isEmpty();
    }

    private ResponseEntity<String> upload(UUID appeal, String name, String type, byte[] content) {
        return as(ANNA).postForEntity("/api/appeals/{id}/attachments", files(name, type, content), String.class, appeal);
    }

    private static HttpEntity<MultiValueMap<String, Object>> files(String name, String type, byte[] content) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(type));
        partHeaders.setContentDispositionFormData("files", name);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("files", new HttpEntity<>(new ByteArrayResource(content), partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(parts, headers);
    }
}
