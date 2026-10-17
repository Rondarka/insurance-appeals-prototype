package ru.mtuci.appeals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.mtuci.appeals.api.ApiModels.CreateAppealRequest;
import ru.mtuci.appeals.api.ApiModels.IntegrationStatusResponse;
import ru.mtuci.appeals.api.ApiModels.MeResponse;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Защита вызовов учётной системы (решение 30): серия сбоев размыкает цепь — подсистема
 * перестаёт обращаться к системе и сразу работает в резервном режиме; когда система
 * вернулась, цепь замыкается сама после пробных вызовов. Ответ 404 сбоем не считается.
 */
class ContractCircuitTest extends IntegrationTest {

    @AfterEach
    void sourceIsBack() {
        contractsSourceUp();
    }

    @Test
    void repeatedFailuresOpenCircuitAndSourceIsLeftAlone() {
        contractsSourceDown();

        // клиент несколько раз открывает кабинет — каждый раз резервный режим
        for (int i = 0; i < 5; i++) {
            assertThat(as(ANNA).getForObject("/api/me", MeResponse.class).contractsUnavailable()).isTrue();
        }
        assertThat(contractsState()).isEqualTo("OPEN");

        // цепь разомкнута: кабинет открывается, а учётная система запросов больше не получает
        int before = clientContractRequests();
        assertThat(as(ANNA).getForObject("/api/me", MeResponse.class).contractsUnavailable()).isTrue();
        assertThat(clientContractRequests()).isEqualTo(before);
    }

    @Test
    void circuitClosesItselfWhenSourceRecovers() {
        contractsSourceDown();
        for (int i = 0; i < 5; i++) {
            as(ANNA).getForObject("/api/me", MeResponse.class);
        }
        assertThat(contractsState()).isEqualTo("OPEN");

        contractsSourceRecovers();

        // после паузы пробные вызовы проходят — договоры снова в кабинете
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .until(() -> !as(ANNA).getForObject("/api/me", MeResponse.class).contractsUnavailable());
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            as(ANNA).getForObject("/api/me", MeResponse.class);
            return contractsState().equals("CLOSED");
        });
    }

    @Test
    void unknownContractIsNotAnOutage() {
        // «договора нет» — нормальный ответ, цепь из-за него не размыкается
        for (int i = 0; i < 6; i++) {
            assertThat(as(ANNA).postForEntity("/api/appeals", new CreateAppealRequest(UUID.randomUUID(), null, null,
                    "TECHNICAL", "LOGIN", Map.of("systemSection", "Личный кабинет", "device", "Android, Chrome",
                            "errorText", "Неверный код"), "Не могу войти", "Код из SMS не принимается."),
                    String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(contractsState()).isEqualTo("CLOSED");
    }

    private String contractsState() {
        IntegrationStatusResponse[] statuses = as(ADMIN).getForObject("/api/integrations", IntegrationStatusResponse[].class);
        return statuses[0].state();
    }
}
