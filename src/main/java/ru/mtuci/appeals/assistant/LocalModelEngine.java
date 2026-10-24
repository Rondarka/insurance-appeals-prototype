package ru.mtuci.appeals.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Адаптер локальной модели (решение 35): OpenAI-совместимый метод /chat/completions — его дают
 * Ollama, LM Studio и vLLM, поэтому модель меняется без изменения кода. Модель работает в
 * контуре страховой: текст обращения не уходит во внешние сервисы.
 *
 * Модель ненадёжна по-своему: отвечает долго, бывает недоступна, отвечает не по формату.
 * Любой такой сбой — AssistantUnavailableException, и подсказку дают правила. После серии
 * сбоев к модели какое-то время не обращаемся, как к учётной системе (решение 30).
 */
@Component
public class LocalModelEngine implements AssistantEngine {

    private static final String TYPE_PROMPT = """
            Ты помогаешь сотруднику страховой компании разбирать обращения клиентов.
            Претензия — требование к страховщику, когда клиент не согласен с его решением или выплатой
            и требует пересмотра (Федеральный закон № 123-ФЗ). Вопрос, просьба или жалоба на обслуживание
            претензией не являются. Ответь строго одним объектом JSON без пояснений:
            {"pretension": true или false, "reason": "короткая цитата из текста клиента"}""";
    private static final String DRAFT_PROMPT = """
            Ты помогаешь сотруднику страховой компании написать клиенту ответ по существу обращения.
            Пиши по-русски, вежливо и по делу. Возьми за основу шаблон: сохрани его структуру и данные.
            Заполни места в квадратных скобках, только если ответ есть в тексте обращения; иначе оставь
            скобки — их заполнит сотрудник. Не придумывай суммы, даты и пункты правил страхования.
            Верни только текст ответа.""";

    private final RestClient restClient;
    private final AssistantProperties.Model model;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;

    public LocalModelEngine(RestClient.Builder builder, AssistantProperties properties, ObjectMapper objectMapper) {
        this.model = properties.model();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Math.min(model.timeout().toMillis(), 3000));
        requestFactory.setReadTimeout((int) model.timeout().toMillis());
        RestClient.Builder client = builder.clone()
                .baseUrl(model.baseUrl())
                .requestFactory(requestFactory);
        if (model.apiKey() != null && !model.apiKey().isBlank()) {
            client.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + model.apiKey());
        }
        this.restClient = client.build();
        this.circuitBreaker = CircuitBreaker.of("assistant-model", CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(model.openDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(exception -> exception instanceof AssistantUnavailableException)
                .build());
    }

    @Override
    public String source() {
        return LOCAL_MODEL;
    }

    @Override
    public Optional<TypeGuess> guessType(AppealText appeal) {
        if ("PRETENSION".equals(appeal.subcategory())) {
            return Optional.empty();
        }
        String answer = complete(TYPE_PROMPT, """
                Тип, который выбрал клиент: %s.
                Тема: %s
                Текст обращения: %s
                %s""".formatted(appeal.typeLabel(), appeal.subject(), appeal.description(), clientMessages(appeal)));
        JsonNode verdict = json(answer);
        if (!verdict.path("pretension").isBoolean()) {
            throw new AssistantUnavailableException("Модель ответила не по формату", null);
        }
        if (!verdict.path("pretension").asBoolean()) {
            return Optional.empty();
        }
        String reason = verdict.path("reason").asText("").trim();
        return Optional.of(new TypeGuess("COMPLAINT", "PRETENSION", reason.isEmpty() ? List.of() : List.of(reason)));
    }

    @Override
    public String draft(DraftInput input) {
        AppealText appeal = input.appeal();
        String answer = complete(DRAFT_PROMPT, """
                Итог рассмотрения: %s.
                Обращение клиента — тема: %s
                %s
                %s
                Шаблон ответа:
                %s
                %s""".formatted(input.outcome().label(), appeal.subject(), appeal.description(), clientMessages(appeal),
                input.filledTemplate(), input.similarAnswers().isEmpty() ? ""
                        : "Как отвечали на похожие обращения:\n" + input.similarAnswers().stream()
                        .map(text -> "- " + text).collect(Collectors.joining("\n"))));
        if (answer.isBlank()) {
            throw new AssistantUnavailableException("Модель вернула пустой ответ", null);
        }
        return answer.trim();
    }

    /** Состояние цепи — для обзора у администратора. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public String modelName() {
        return model.name();
    }

    public String baseUrl() {
        return model.baseUrl();
    }

    private String complete(String system, String user) {
        try {
            return circuitBreaker.executeSupplier(() -> call(system, user));
        } catch (CallNotPermittedException exception) {
            throw new AssistantUnavailableException("Локальная модель недоступна, подсказки дают правила", exception);
        }
    }

    private String call(String system, String user) {
        try {
            ChatCompletion completion = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model.name(),
                            "temperature", 0.2,
                            "stream", false,
                            "messages", List.of(
                                    Map.of("role", "system", "content", system),
                                    Map.of("role", "user", "content", user))))
                    .retrieve()
                    .body(ChatCompletion.class);
            if (completion == null || completion.choices() == null || completion.choices().isEmpty()
                    || completion.choices().get(0).message() == null) {
                throw new AssistantUnavailableException("Модель вернула пустой ответ", null);
            }
            return String.valueOf(completion.choices().get(0).message().content());
        } catch (RestClientException exception) {
            throw new AssistantUnavailableException("Локальная модель не ответила", exception);
        }
    }

    /** Модели любят обрамлять JSON пояснениями и ```-блоками — берём сам объект. */
    private JsonNode json(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AssistantUnavailableException("Модель ответила не по формату", null);
        }
        try {
            return objectMapper.readTree(answer.substring(start, end + 1));
        } catch (JsonProcessingException exception) {
            throw new AssistantUnavailableException("Модель ответила не по формату", exception);
        }
    }

    private static String clientMessages(AppealText appeal) {
        return appeal.clientMessages().isEmpty() ? ""
                : "Сообщения клиента в переписке:\n" + String.join("\n", appeal.clientMessages());
    }

    record ChatCompletion(List<Choice> choices) {

        record Choice(Message message) {
        }

        record Message(String role, String content) {
        }
    }
}
