package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.RoutingRule;

import java.util.Optional;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    Optional<RoutingRule> findByCategoryAndSubcategory(String category, String subcategory);

    Optional<RoutingRule> findFirstByDepartmentCodeOrderBySlaHoursAsc(String departmentCode);
}
