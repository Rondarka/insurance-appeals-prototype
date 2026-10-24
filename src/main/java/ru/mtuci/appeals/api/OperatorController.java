package ru.mtuci.appeals.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.OperatorAppealRequest;
import ru.mtuci.appeals.api.ApiModels.OperatorClientResponse;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.integration.ExternalContract;
import ru.mtuci.appeals.security.CurrentUser;
import ru.mtuci.appeals.service.AppealRegistrationService;
import ru.mtuci.appeals.service.AppealService;
import ru.mtuci.appeals.service.DirectoryService;

import java.util.UUID;

/**
 * Оператор контакт-центра (решение 38): находит клиента и регистрирует обращение с его слов.
 * Доступ только у роли OPERATOR, закрыт в SecurityConfig.
 */
@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private final ContractsClient contractsClient;
    private final DirectoryService directoryService;
    private final AppealService appealService;
    private final AppealRegistrationService registration;

    public OperatorController(ContractsClient contractsClient, DirectoryService directoryService,
                              AppealService appealService, AppealRegistrationService registration) {
        this.contractsClient = contractsClient;
        this.directoryService = directoryService;
        this.appealService = appealService;
        this.registration = registration;
    }

    /**
     * Клиент по номеру договора. В эксплуатации оператор ищет клиента в CDI по телефону или
     * паспорту; в стенде CDI нет, поэтому поиск идёт по номеру договора в учётной системе.
     */
    @GetMapping("/clients")
    OperatorClientResponse client(@RequestParam String policyNumber) {
        UUID counterpartyId = contractsClient.contractByPolicyNumber(policyNumber.trim())
                .map(ExternalContract::counterpartyId)
                .orElseThrow(() -> new NotFoundException("Договор с номером " + policyNumber.trim() + " не найден"));
        return new OperatorClientResponse(counterpartyId, directoryService.contractsOf(counterpartyId),
                appealService.ofClient(counterpartyId));
    }

    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.CREATED)
    AppealDetailResponse register(CurrentUser operator, @Valid @RequestBody OperatorAppealRequest request) {
        return registration.createByOperator(operator, request);
    }
}
