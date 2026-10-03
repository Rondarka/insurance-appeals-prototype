package ru.mtuci.appeals.service;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.ClientResponse;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EmployeeResponse;
import ru.mtuci.appeals.domain.ContractSnapshot;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.Employee;
import ru.mtuci.appeals.integration.ContractsClient;
import ru.mtuci.appeals.integration.ExternalContract;
import ru.mtuci.appeals.repository.ClientRepository;
import ru.mtuci.appeals.repository.EmployeeRepository;

import java.util.List;

@Service
public class DirectoryService {

    private final ClientRepository clientRepository;
    private final ContractsClient contractsClient;
    private final EmployeeRepository employeeRepository;

    public DirectoryService(ClientRepository clientRepository,
                            ContractsClient contractsClient,
                            EmployeeRepository employeeRepository) {
        this.clientRepository = clientRepository;
        this.contractsClient = contractsClient;
        this.employeeRepository = employeeRepository;
    }

    /** Без общей транзакции: договоры приходят по сети из учётной системы. */
    public List<ClientResponse> clients() {
        return clientRepository.findAll(Sort.by("fullName")).stream()
                .map(client -> new ClientResponse(
                        client.getId(),
                        client.getFullName(),
                        client.getEmail(),
                        client.getPhone(),
                        contractsClient.clientContracts(client.getId()).stream()
                                .map(ExternalContract::toSnapshot)
                                .map(this::toContract)
                                .toList()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> employees() {
        return employeeRepository.findAll(Sort.by("fullName")).stream()
                .map(this::toEmployee)
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
