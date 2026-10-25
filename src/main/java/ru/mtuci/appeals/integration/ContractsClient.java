package ru.mtuci.appeals.integration;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Адаптер к учётной системе договоров страховой. Единственное место, где подсистема
 * знает, как устроен внешний интерфейс; остальной код работает с {@link ExternalContract}.
 *
 * 404 означает «договора нет», а не сбой. Всё остальное, что не 2xx, и отсутствие
 * ответа считаются сбоем источника: {@link ContractSourceUnavailableException}.
 *
 * Вызовы защищены (решение 30). Цепь одна на всю учётную систему: если из последних
 * вызовов слишком многие закончились сбоем, она размыкается, и подсистема какое-то время
 * не обращается к системе вовсе: сразу работает в резервном режиме, не заставляя клиента
 * ждать таймаута и не добивая лежащую систему. Повторы разведены по путям: в кабинете
 * человек ждёт ответа: короткий таймаут и один быстрый повтор; в фоне повторов внутри нет,
 * паузы между попытками задаёт фоновая привязка договора. Повторяются только таймаут,
 * отсутствие соединения и 5xx: ответ 4xx повтор не исправит.
 */
@Component
public class ContractsClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry interactiveRetry;

    public ContractsClient(RestClient.Builder builder, ContractsProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) properties.timeout().toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();

        ContractsProperties.Circuit circuit = properties.circuit();
        this.circuitBreaker = CircuitBreaker.of("contracts", CircuitBreakerConfig.custom()
                .slidingWindowSize(circuit.slidingWindow())
                .minimumNumberOfCalls(circuit.minimumCalls())
                .failureRateThreshold(circuit.failureRateThreshold())
                .waitDurationInOpenState(circuit.openDuration())
                .permittedNumberOfCallsInHalfOpenState(2)
                // сбоем считается только недоступность; ответ 4xx: ошибка запроса, а не состояние системы
                .recordException(ContractsClient::isOutage)
                .build());
        this.interactiveRetry = Retry.of("contracts-interactive", RetryConfig.custom()
                .maxAttempts(properties.retry().maxAttempts())
                .waitDuration(properties.retry().pause())
                .retryOnException(ContractsClient::isOutage)
                .build());
    }

    /** Договор по идентификатору; пусто, если учётная система такого договора не знает. */
    public Optional<ExternalContract> contract(UUID contractId) {
        return interactive(() -> fetch(ExternalContract.class, "/contracts/{id}", contractId));
    }

    /** Договор по номеру полиса: так оператор находит клиента, который звонит (решение 38). */
    public Optional<ExternalContract> contractByPolicyNumber(String policyNumber) {
        return interactive(() -> restClient.get()
                .uri(builder -> builder.path("/contracts").queryParam("policyNumber", policyNumber).build())
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new ContractSourceUnavailableException(
                                "Учётная система договоров ответила ошибкой " + response.getStatusCode().value(),
                                response.getStatusCode().value(), null);
                    }
                    ExternalContract[] found = response.bodyTo(ExternalContract[].class);
                    return found == null || found.length == 0 ? Optional.<ExternalContract>empty() : Optional.of(found[0]);
                }));
    }

    /** Все договоры клиента, включая истёкшие и прекращённые; пусто, если клиент неизвестен. */
    public List<ExternalContract> clientContracts(UUID counterpartyId) {
        return interactive(() -> fetchClientContracts(counterpartyId));
    }

    /** То же для фоновой привязки, но без повтора внутри: там никто не ждёт. */
    public List<ExternalContract> clientContractsInBackground(UUID counterpartyId) {
        return guarded(() -> fetchClientContracts(counterpartyId));
    }

    /** Состояние цепи: для администрирования и проверок. */
    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private <T> T interactive(Supplier<T> call) {
        return Retry.decorateSupplier(interactiveRetry, () -> guarded(call)).get();
    }

    private <T> T guarded(Supplier<T> call) {
        try {
            return circuitBreaker.executeSupplier(call);
        } catch (CallNotPermittedException exception) {
            throw new ContractSourceUnavailableException(
                    "Учётная система договоров недоступна, обращения к ней приостановлены",
                    ContractSourceUnavailableException.CIRCUIT_OPEN, exception);
        }
    }

    /** Таймаут, нет соединения или 5xx. Разомкнутая цепь: не повод повторять. */
    private static boolean isOutage(Throwable exception) {
        return exception instanceof ContractSourceUnavailableException unavailable
                && (unavailable.getStatus() == 0 || unavailable.getStatus() >= 500);
    }

    private List<ExternalContract> fetchClientContracts(UUID counterpartyId) {
        return fetch(ExternalContract[].class, "/clients/{id}/contracts", counterpartyId)
                .map(List::of)
                .orElse(List.of());
    }

    private <T> Optional<T> fetch(Class<T> type, String uri, Object... variables) {
        try {
            return restClient.get().uri(uri, variables).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) {
                    return Optional.empty();
                }
                if (response.getStatusCode().isError()) {
                    throw new ContractSourceUnavailableException(
                            "Учётная система договоров ответила ошибкой " + status, status, null);
                }
                return Optional.ofNullable(response.bodyTo(type));
            });
        } catch (ResourceAccessException exception) {
            throw new ContractSourceUnavailableException(
                    "Учётная система договоров недоступна", 0, exception);
        }
    }
}
