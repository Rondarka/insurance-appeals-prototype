package ru.mtuci.appeals.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mtuci.appeals.domain.AppealCategory;
import ru.mtuci.appeals.domain.RoutingRule;

import java.util.Optional;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    Optional<RoutingRule> findByCategoryAndSubcategory(AppealCategory category, String subcategory);

    Optional<RoutingRule> findFirstByDepartmentCodeOrderBySlaHoursAsc(String departmentCode);
}
