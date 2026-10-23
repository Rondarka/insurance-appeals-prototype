package ru.mtuci.appeals.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.mtuci.appeals.api.ApiModels.AnalyticsResponse;
import ru.mtuci.appeals.api.ApiModels.NewNormVersionRequest;
import ru.mtuci.appeals.api.ApiModels.NormVersionResponse;
import ru.mtuci.appeals.api.ApiModels.RoutingSettingsResponse;
import ru.mtuci.appeals.api.ApiModels.SaveRoutingRuleRequest;
import ru.mtuci.appeals.api.ApiModels.SettingsAuditResponse;
import ru.mtuci.appeals.security.CurrentUser;
import ru.mtuci.appeals.service.AnalyticsService;
import ru.mtuci.appeals.service.SettingsService;

import java.util.List;

/** Настройки подсистемы — только администратору (решение 34). Доступ закрыт в SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SettingsService settings;
    private final AnalyticsService analytics;

    public AdminController(SettingsService settings, AnalyticsService analytics) {
        this.settings = settings;
        this.analytics = analytics;
    }

    @GetMapping("/routing-rules")
    RoutingSettingsResponse routing() {
        return settings.routing();
    }

    @PutMapping("/routing-rules")
    RoutingSettingsResponse saveRule(CurrentUser user, @Valid @RequestBody SaveRoutingRuleRequest request) {
        settings.saveRule(user, request);
        return settings.routing();
    }

    @DeleteMapping("/routing-rules/{category}/{subcategory}")
    RoutingSettingsResponse deleteRule(CurrentUser user, @PathVariable String category, @PathVariable String subcategory,
                                       @RequestParam String reason) {
        settings.deleteRule(user, category, subcategory, reason);
        return settings.routing();
    }

    @GetMapping("/norms")
    List<NormVersionResponse> norms() {
        return settings.norms();
    }

    @PostMapping("/norms")
    @ResponseStatus(HttpStatus.CREATED)
    List<NormVersionResponse> addNorm(CurrentUser user, @Valid @RequestBody NewNormVersionRequest request) {
        settings.addNormVersion(user, request);
        return settings.norms();
    }

    @GetMapping("/settings-audit")
    List<SettingsAuditResponse> audit() {
        return settings.auditLog();
    }

    /** Показатели обращений за период (решение 23); product — вид страхования, пусто — все. */
    @GetMapping("/analytics")
    AnalyticsResponse analytics(@RequestParam(defaultValue = "180") int days,
                                @RequestParam(required = false) String product) {
        return analytics.analytics(days, product);
    }
}
