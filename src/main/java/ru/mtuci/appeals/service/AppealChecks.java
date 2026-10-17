package ru.mtuci.appeals.service;

import org.springframework.stereotype.Component;
import ru.mtuci.appeals.api.ApiModels.CheckResponse;
import ru.mtuci.appeals.domain.Appeal;
import ru.mtuci.appeals.domain.ContractSnapshot;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Проверки, которые подсистема делает за сотрудника (решение 21): попадает ли событие в
 * срок действия договора, действовал ли договор, не повторное ли это обращение. Сотрудник
 * видит их сразу, а не выясняет вручную; решение по-прежнему принимает он.
 */
@Component
public class AppealChecks {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Duration REPEAT_WINDOW = Duration.ofDays(30);
    private static final Map<String, String> CONTRACT_STATUS = Map.of(
            "NOT_STARTED", "ещё не вступил в силу",
            "EXPIRED", "истёк",
            "TERMINATED", "прекращён досрочно"
    );

    public List<CheckResponse> run(Appeal appeal, Map<String, String> details, List<Appeal> clientAppeals) {
        List<CheckResponse> checks = new ArrayList<>();
        ContractSnapshot contract = appeal.getContract();
        if (contract == null) {
            checks.add(warning("CONTRACT_NOT_LINKED",
                    "Договор ещё не привязан — проверки покрытия и статуса появятся после привязки"));
            return checks;
        }

        String incident = details.get("incidentDate");
        if (incident != null) {
            checks.add(coverage(contract, incident));
        }

        String status = CONTRACT_STATUS.get(contract.getStatus());
        checks.add(status == null
                ? ok("CONTRACT_STATUS", "Договор действовал на момент обращения")
                : warning("CONTRACT_STATUS", "На момент обращения договор " + status));

        List<String> repeats = clientAppeals.stream()
                .filter(other -> !other.getId().equals(appeal.getId()))
                .filter(other -> other.getContract() != null
                        && other.getContract().getContractId().equals(contract.getContractId()))
                .filter(other -> other.getCategory().equals(appeal.getCategory()))
                .filter(other -> other.getCreatedAt().isBefore(appeal.getCreatedAt())
                        && other.getCreatedAt().isAfter(appeal.getCreatedAt().minus(REPEAT_WINDOW)))
                .map(Appeal::getPublicNumber)
                .toList();
        checks.add(repeats.isEmpty()
                ? ok("REPEATED", "Первое обращение на эту тему по договору за 30 дней")
                : warning("REPEATED", "Повторное обращение: по этому договору на ту же тему за 30 дней — "
                        + repeats.size() + " (" + String.join(", ", repeats) + ")"));
        return checks;
    }

    /**
     * Покрытие на дату события: validFrom ≤ D ≤ validTo и, если договор прекращён досрочно,
     * D раньше даты прекращения (docs/integration-contract.md). Статус договора на сегодня
     * для этого не годится: случай мог произойти, пока договор действовал.
     */
    private static CheckResponse coverage(ContractSnapshot contract, String incident) {
        LocalDate date;
        try {
            date = LocalDate.parse(incident);
        } catch (DateTimeParseException exception) {
            return warning("COVERAGE", "Дата происшествия не распознана — проверить покрытие вручную");
        }
        String day = date.format(DAY);
        if (date.isBefore(contract.getValidFrom()) || date.isAfter(contract.getValidTo())) {
            return warning("COVERAGE", "Происшествие " + day + " вне периода договора "
                    + contract.getValidFrom().format(DAY) + " – " + contract.getValidTo().format(DAY));
        }
        if (contract.getTerminatedOn() != null && !date.isBefore(contract.getTerminatedOn())) {
            return warning("COVERAGE", "Происшествие " + day + " после досрочного прекращения договора "
                    + contract.getTerminatedOn().format(DAY));
        }
        return ok("COVERAGE", "Происшествие " + day + " в периоде действия договора");
    }

    private static CheckResponse ok(String code, String message) {
        return new CheckResponse(code, "OK", message);
    }

    private static CheckResponse warning(String code, String message) {
        return new CheckResponse(code, "WARNING", message);
    }
}
