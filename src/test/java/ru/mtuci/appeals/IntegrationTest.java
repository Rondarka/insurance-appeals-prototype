package ru.mtuci.appeals;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.AppealSummaryResponse;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.domain.AppealCategory;
import ru.mtuci.appeals.domain.AppealStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
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
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    // данные из миграции V4
    protected static final UUID CLIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    protected static final UUID CASCO_CONTRACT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    protected static final UUID SUPPORT_SPECIALIST = UUID.fromString("31111111-1111-1111-1111-111111111111");
    protected static final UUID SUPPORT_SUPERVISOR = UUID.fromString("32222222-2222-2222-2222-222222222222");
    protected static final UUID CLAIMS_SUPERVISOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    protected static final UUID CLAIMS_SPECIALIST = UUID.fromString("34444444-4444-4444-4444-444444444444");

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
        registry.add("appeals.contracts.base-url",
                () -> "http://" + CONTRACTS_STUB.getHost() + ":" + CONTRACTS_STUB.getMappedPort(8080));
    }

    @Autowired
    protected TestRestTemplate rest;

    protected ResponseEntity<AppealDetailResponse> postAppeal(CreateAppealRequest request) {
        return rest.postForEntity("/api/appeals", request, AppealDetailResponse.class);
    }

    protected AppealDetailResponse createAppeal(CreateAppealRequest request) {
        ResponseEntity<AppealDetailResponse> response = postAppeal(request);
        assertThat(response.getStatusCode().value()).as("создание обращения").isEqualTo(201);
        return response.getBody();
    }

    protected AppealDetailResponse getAppeal(UUID id) {
        return rest.getForObject("/api/appeals/{id}", AppealDetailResponse.class, id);
    }

    /** Маршрутизация асинхронная: ждём, пока потребитель из очереди appeal.routing её выполнит. */
    protected AppealDetailResponse awaitRouted(UUID id) {
        return await().atMost(Duration.ofSeconds(15))
                .until(() -> getAppeal(id), appeal -> appeal.status() != AppealStatus.PENDING_ROUTING);
    }

    protected int appealsOfClient(UUID clientId) {
        AppealSummaryResponse[] appeals = rest.getForObject(
                "/api/appeals?clientId={id}", AppealSummaryResponse[].class, clientId);
        return appeals.length;
    }

    /** Страховой случай по КАСКО: маршрутизируется в урегулирование убытков. */
    protected static CreateAppealRequest cascoClaim() {
        return new CreateAppealRequest(
                CLIENT, CASCO_CONTRACT, AppealCategory.CLAIM, "AUTO",
                Map.of("incidentDate", "2026-09-10", "incidentPlace", "г. Москва, Ленинградское шоссе, д. 39"),
                "ДТП на Ленинградском шоссе, повреждено крыло",
                "Произошло ДТП с участием второго автомобиля, оформлен европротокол.");
    }

    /** Техническая проблема входа: маршрутизируется в техническую поддержку. */
    protected static CreateAppealRequest loginProblem() {
        return new CreateAppealRequest(
                CLIENT, CASCO_CONTRACT, AppealCategory.TECHNICAL, "LOGIN",
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
