package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.Department;

import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByCode(String code);
}
