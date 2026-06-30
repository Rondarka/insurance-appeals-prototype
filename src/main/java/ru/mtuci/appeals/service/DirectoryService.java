package ru.mtuci.appeals.service;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mtuci.appeals.api.ApiModels.ClientResponse;
import ru.mtuci.appeals.api.ApiModels.ContractResponse;
import ru.mtuci.appeals.api.ApiModels.DepartmentResponse;
import ru.mtuci.appeals.api.ApiModels.EmployeeResponse;
import ru.mtuci.appeals.domain.Department;
import ru.mtuci.appeals.domain.Employee;
import ru.mtuci.appeals.domain.InsuranceContract;
import ru.mtuci.appeals.repository.ClientRepository;
import ru.mtuci.appeals.repository.EmployeeRepository;
import ru.mtuci.appeals.repository.InsuranceContractRepository;

import java.util.List;

@Service
public class DirectoryService {

    private final ClientRepository clientRepository;
    private final InsuranceContractRepository contractRepository;
    private final EmployeeRepository employeeRepository;

    public DirectoryService(ClientRepository clientRepository,
                            InsuranceContractRepository contractRepository,
                            EmployeeRepository employeeRepository) {
        this.clientRepository = clientRepository;
        this.contractRepository = contractRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> clients() {
        return clientRepository.findAll(Sort.by("fullName")).stream()
                .map(client -> new ClientResponse(
                        client.getId(),
                        client.getFullName(),
                        client.getEmail(),
                        client.getPhone(),
                        contractRepository.findByClientIdOrderByValidToDesc(client.getId()).stream()
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

    public ContractResponse toContract(InsuranceContract contract) {
        return new ContractResponse(
                contract.getId(),
                contract.getPolicyNumber(),
                contract.getInsuranceType(),
                contract.getInsuredObject(),
                contract.getValidFrom(),
                contract.getValidTo(),
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
