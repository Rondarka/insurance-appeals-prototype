package ru.mtuci.appeals.service;

import org.springframework.stereotype.Service;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EmployeeResponse;
import ru.mtuci.appeals.api.ApiModels.MeResponse;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.Employee;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.integration.ExternalContract;
import ru.mtuci.appeals.repository.DepartmentRepository;
import ru.mtuci.appeals.security.CurrentUser;

import java.util.List;
import java.util.UUID;

@Service
public class DirectoryService {

    private final ContractsClient contractsClient;
    private final DepartmentRepository departmentRepository;

    public DirectoryService(ContractsClient contractsClient, DepartmentRepository departmentRepository) {
        this.contractsClient = contractsClient;
        this.departmentRepository = departmentRepository;
    }

    /** Профиль по токену: клиенту — его договоры, сотруднику — его подразделение. */
    public MeResponse me(CurrentUser user) {
        return new MeResponse(
                user.subject(),
                user.name(),
                user.email(),
                user.roles().stream().sorted().toList(),
                user.counterpartyId(),
                user.isEmployee() && user.departmentCode() != null
                        ? departmentRepository.findByCode(user.departmentCode()).map(this::toDepartment).orElse(null)
                        : null,
                user.isClient() ? contractsOf(user.requireCounterparty()) : List.of()
        );
    }

    /** Договоры клиента из учётной системы — по сети, поэтому без транзакции базы. */
    public List<ContractResponse> contractsOf(UUID counterpartyId) {
        return contractsClient.clientContracts(counterpartyId).stream()
                .map(ExternalContract::toSnapshot)
                .map(this::toContract)
                .toList();
    }

    public ContractResponse toContract(ContractSnapshot contract) {
        return new ContractResponse(
                contract.getContractId(),
                contract.getPolicyNumber(),
                contract.getProductCode(),
                contract.getProductName(),
                contract.getInsuredObject(),
                contract.getValidFrom(),
                contract.getValidTo(),
                contract.getTerminatedOn(),
                contract.getStatus()
        );
    }

    public EmployeeResponse toEmployee(Employee employee) {
        if (employee == null) {
            return null;
        }
        return new EmployeeResponse(
                employee.getId(),
                employee.getFullName(),
                employee.getRole(),
                toDepartment(employee.getDepartment())
        );
    }

    public DepartmentResponse toDepartment(Department department) {
        if (department == null) {
            return null;
        }
        return new DepartmentResponse(
                department.getId(),
                department.getCode(),
                department.getName(),
                department.getDescription()
        );
    }
}
