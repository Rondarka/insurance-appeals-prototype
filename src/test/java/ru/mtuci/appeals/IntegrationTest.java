package ru.mtuci.appeals;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import ru.mtuci.appeals.TestIdentityProvider.TestUser;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.domain.AppealStatus;
import ru.mtuci.appeals.integration.ContractsClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Основа интеграционных тестов: приложение целиком против настоящих PostgreSQL и RabbitMQ
 * и заглушки учётной системы договоров — в контейнерах тех же версий, что в compose.yaml.
 * Заглушка отвечает по тем же файлам stubs/contracts, что и в стенде. Контейнеры поднимаются один раз на весь
 * прогон и общие для всех тестовых классов; схему и справочные данные создаёт Flyway.
 *
 * Тесты не очищают базу: каждый работает со своими обращениями и проверяет их по id.
 *
 * Запросы идут от имени пользователей с токенами {@link TestIdentityProvider}: as(ANNA),
 * as(CLAIMS_SUPERVISOR) и т. д. Идентификаторы клиента и сотрудника в запросах не передаются.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    // контрагенты и договоры — из заглушки учётной системы (stubs/contracts)
    protected static final UUID ANNA_COUNTERPARTY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final UUID PETR_COUNTERPARTY = UUID.fromString("19999999-9999-9999-9999-999999999999");
    protected static final UUID CASCO_CONTRACT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    protected static final TestUser ANNA = TestUser.client(
            "c0a00001-0000-0000-0000-000000000001", "Анна Смирнова", "anna@example.ru", ANNA_COUNTERPARTY);
    protected static final TestUser PETR = TestUser.client(
            "c0a00001-0000-0000-0000-000000000002", "Пётр Иванов", "petr@example.ru", PETR_COUNTERPARTY);

    // сотрудники — только из токена: идентификатор IdP, имя, роль и подразделение (решение 22)
    protected static final TestUser SUPPORT_SPECIALIST = TestUser.employee(
            UUID.fromString("31111111-1111-1111-1111-111111111111"), "Елена Соколова", "SPECIALIST", "SUPPORT");
    protected static final TestUser SUPPORT_SUPERVISOR = TestUser.employee(
            UUID.fromString("32222222-2222-2222-2222-222222222222"), "Андрей Волков", "SUPERVISOR", "SUPPORT");
    protected static final TestUser CLAIMS_SUPERVISOR = TestUser.employee(
            UUID.fromString("33333333-3333-3333-3333-333333333333"), "Марина Орлова", "SUPERVISOR", "CLAIMS");
    protected static final TestUser CLAIMS_SPECIALIST = TestUser.employee(
            UUID.fromString("34444444-4444-4444-4444-444444444444"), "Сергей Лебедев", "SPECIALIST", "CLAIMS");
    protected static final TestUser ADMIN = new TestUser(
            "a0000000-0000-0000-0000-00000000000a", "Администратор подсистемы", null, List.of("ADMIN"), null, null);
    protected static final TestUser OPERATOR = new TestUser(
            "0e000000-0000-0000-0000-000000000001", "Наталья Ковалёва", null, List.of("OPERATOR"), null, null);

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.10-alpine").asCompatibleSubstituteFor("postgres"));

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.1-management-alpine").asCompatibleSubstituteFor("rabbitmq"));

    private static final GenericContainer<?> CONTRACTS_STUB = new GenericContainer<>(
            DockerImageName.parse("wiremock/wiremock:3.13.2-alpine"))
            .withCopyFileToContainer(MountableFile.forHostPath("stubs/contracts"), "/home/wiremock")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    private static final String ATTACHMENTS_DIR = createTempDir();

    static {
        POSTGRES.start();
        RABBIT.start();
        CONTRACTS_STUB.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // в compose приложение ходит в отдельный vhost, в тестовом контейнере есть только корневой
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("appeals.attachments.path", () -> ATTACHMENTS_DIR);
        // планировщик эскалации в тестах не запускается сам: тесты вызывают проход с нужной датой
        registry.add("appeals.escalation.initial-delay", () -> "PT24H");
        registry.add("appeals.contract-link.initial-delay", () -> "PT24H");
        // разомкнутая цепь в тестах ждёт недолго
        registry.add("appeals.contracts.circuit.open-duration", () -> "PT2S");
        registry.add("appeals.contracts.base-url",
                () -> "http://" + CONTRACTS_STUB.getHost() + ":" + CONTRACTS_STUB.getMappedPort(8080));
        // локальная модель помощника — поддельная, как поставщик идентификации (решение 35)
        registry.add("appeals.assistant.model.base-url", TestLocalModel::baseUrl);
        registry.add("appeals.attachments.antivirus.host", () -> "localhost");
        registry.add("appeals.attachments.antivirus.port", TestAntivirus::port);
        registry.add("appeals.attachments.antivirus.timeout", () -> "PT2S");
        registry.add("appeals.assistant.model.timeout", () -> "2s");
        registry.add("appeals.assistant.model.open-duration", () -> "PT1S");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestIdentityProvider.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestIdentityProvider::jwkSetUri);
    }

    /** Без токена — для проверок отказа. */
    @Autowired
    protected TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Autowired
    private ContractsClient contractsClient;

    /** Запросы от имени пользователя: каждый с его токеном. */
    protected TestRestTemplate as(TestUser user) {
        return withToken(TestIdentityProvider.token(user));
    }

    protected TestRestTemplate withToken(String token) {
        return new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** Правило заглушки, которым «кладётся» учётная система: важнее всех остальных. */
    private static final String OUTAGE_MAPPING = "0e7a6e00-0000-4000-8000-000000000503";

    /** Учётная система договоров недоступна: на любой запрос — 503, пока не вызван contractsSourceUp. */
    protected void contractsSourceDown() {
        String mapping = """
                {"id": "%s", "priority": 1,
                 "request": {"method": "ANY", "urlPattern": "/(clients|contracts)/.*"},
                 "response": {"status": 503, "jsonBody": {"message": "maintenance"}}}""".formatted(OUTAGE_MAPPING);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = contractsStubAdmin().postForEntity(
                "/__admin/mappings", new HttpEntity<>(mapping, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("учётная система положена").isTrue();
    }

    /** Брокер остановлен: соединения рвутся, публикация отказывает, очереди и сообщения сохраняются. */
    protected void brokerDown() throws Exception {
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
    }

    protected void brokerUp() throws Exception {
        assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
    }

    /** Система вернулась, и цепь сброшена — следующий тест начинает с чистого листа. */
    protected void contractsSourceUp() {
        contractsSourceRecovers();
        contractsClient.circuitBreaker().reset();
    }

    /** Система вернулась, а подсистема узнаёт об этом сама — через пробные вызовы цепи. */
    protected void contractsSourceRecovers() {
        contractsStubAdmin().delete("/__admin/mappings/" + OUTAGE_MAPPING);
    }

    /** Сколько запросов о договорах клиентов получила учётная система. */
    protected int clientContractRequests() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> count = contractsStubAdmin().postForObject("/__admin/requests/count",
                new HttpEntity<>("{\"method\": \"GET\", \"urlPathPattern\": \"/clients/.*\"}", headers), Map.class);
        return ((Number) count.get("count")).intValue();
    }

    private static TestRestTemplate contractsStubAdmin() {
        return new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://" + CONTRACTS_STUB.getHost() + ":" + CONTRACTS_STUB.getMappedPort(8080)));
    }

    protected ResponseEntity<AppealDetailResponse> postAppeal(CreateAppealRequest request) {
        return as(ANNA).postForEntity("/api/appeals", request, AppealDetailResponse.class);
    }

    protected AppealDetailResponse createAppeal(CreateAppealRequest request) {
        ResponseEntity<AppealDetailResponse> response = postAppeal(request);
        assertThat(response.getStatusCode().value()).as("создание обращения").isEqualTo(201);
        return response.getBody();
    }

    /** Обращение глазами его автора — Анны: все тестовые обращения создаёт она. */
    protected AppealDetailResponse getAppeal(UUID id) {
        return as(ANNA).getForObject("/api/appeals/{id}", AppealDetailResponse.class, id);
    }

    /** Маршрутизация асинхронная: ждём, пока потребитель из очереди appeal.routing её выполнит. */
    protected AppealDetailResponse awaitRouted(UUID id) {
        return await().atMost(Duration.ofSeconds(15))
                .until(() -> getAppeal(id), appeal -> appeal.status() != AppealStatus.PENDING_ROUTING);
    }

    protected int appealsOf(TestUser client) {
        return as(client).getForObject("/api/appeals", AppealSummaryResponse[].class).length;
    }

    /** Страховой случай по КАСКО: маршрутизируется в урегулирование убытков. */
    protected static CreateAppealRequest cascoClaim() {
        return new CreateAppealRequest(
                CASCO_CONTRACT, null, null, "CLAIM", "AUTO",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "г. Москва, Ленинградское шоссе, д. 39"),
                "ДТП на Ленинградском шоссе, повреждено крыло",
                "Произошло ДТП с участием второго автомобиля, оформлен европротокол.");
    }

    /** Техническая проблема входа: маршрутизируется в техническую поддержку. */
    protected static CreateAppealRequest loginProblem() {
        return new CreateAppealRequest(
                CASCO_CONTRACT, null, null, "TECHNICAL", "LOGIN",
                Map.of("systemSection", "Личный кабинет", "device", "Android, Chrome", "errorText", "Неверный код"),
                "Не могу войти в личный кабинет",
                "При входе приходит код, но система сообщает, что он неверный.");
    }

    private static String createTempDir() {
        try {
            return Files.createTempDirectory("appeals-attachments").toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
