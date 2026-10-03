package ru.mtuci.appeals.integration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Адаптер к учётной системе договоров страховой. Единственное место, где подсистема
 * знает, как устроен внешний интерфейс; остальной код работает с {@link ExternalContract}.
 *
 * 404 — это ответ «договора нет», а не сбой. Всё остальное, что не 2xx, и отсутствие
 * ответа — сбой источника: {@link ContractSourceUnavailableException}.
 */
@Component
public class ContractsClient {

    private final RestClient restClient;

    public ContractsClient(RestClient.Builder builder, ContractsProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) properties.timeout().toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** Договор по идентификатору; пусто, если учётная система такого договора не знает. */
    public Optional<ExternalContract> contract(UUID contractId) {
        return fetch(ExternalContract.class, "/contracts/{id}", contractId);
    }

    /** Все договоры клиента, включая истёкшие и прекращённые; пусто, если клиент неизвестен. */
    public List<ExternalContract> clientContracts(UUID counterpartyId) {
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
