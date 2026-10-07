package ru.mtuci.appeals.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Подсистема — OAuth2 Resource Server (решение 22): никого не аутентифицирует сама, а
 * проверяет токен поставщика идентификации — подпись, издателя, аудиторию и срок
 * (настройки в application.yml). Роли берутся из поля roles токена.
 */
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private static final String[] EMPLOYEE = {"SPECIALIST", "SUPERVISOR"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // токен приходит в заголовке, cookie-сессии нет — подделывать межсайтовый запрос нечем
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/appeals").hasRole("CLIENT")
                        .requestMatchers(HttpMethod.POST, "/api/appeals/*/status").hasAnyRole(EMPLOYEE)
                        .requestMatchers(HttpMethod.POST, "/api/appeals/*/transfer-requests").hasAnyRole(EMPLOYEE)
                        .requestMatchers("/api/transfer-requests/**").hasRole("SUPERVISOR")
                        .requestMatchers("/api/departments", "/api/events").hasAnyRole(EMPLOYEE)
                        .requestMatchers("/api/**").authenticated()
                        // интерфейс и его настройки входа открыты: без них не войти
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromToken())));
        return http.build();
    }

    private static JwtAuthenticationConverter rolesFromToken() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
