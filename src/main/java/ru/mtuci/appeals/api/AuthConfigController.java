package ru.mtuci.appeals.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Где интерфейсу входить: адрес поставщика идентификации и идентификатор клиента OAuth.
 * Открыт без токена — без этих данных войти нельзя. Секретов здесь нет: клиент
 * публичный, вход по коду авторизации с PKCE.
 */
@RestController
public class AuthConfigController {

    public record AuthConfigResponse(String issuer, String clientId) {
    }

    private final AuthConfigResponse config;

    public AuthConfigController(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${appeals.auth.ui-client-id}") String clientId) {
        this.config = new AuthConfigResponse(issuer, clientId);
    }

    @GetMapping("/auth-config")
    AuthConfigResponse authConfig() {
        return config;
    }
}
