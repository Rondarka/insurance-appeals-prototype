package ru.mtuci.appeals;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Поставщик идентификации для тестов: подписывает токены своим ключом и отдаёт открытый
 * ключ по HTTP, как Keycloak. Подсистема проверяет токены той же конфигурацией, что в
 * эксплуатации, — подпись, издатель, аудитория, срок. Проверяются правила подсистемы,
 * а не настройка Keycloak: её проверяет вход в стенде.
 */
final class TestIdentityProvider {

    static final String ISSUER = "http://test-idp/realms/insurer";
    static final String AUDIENCE = "insurance-appeals";

    private static final RSAKey KEY = generateKey("test-key");
    private static final HttpServer SERVER = startKeyServer();

    /** Пользователь IdP: у клиента есть counterpartyId, у сотрудника — подразделение. */
    record TestUser(String subject, String name, String email, List<String> roles,
                    UUID counterpartyId, String department) {

        static TestUser client(String subject, String name, String email, UUID counterpartyId) {
            return new TestUser(subject, name, email, List.of("CLIENT"), counterpartyId, null);
        }

        static TestUser employee(UUID id, String name, String role, String department) {
            return new TestUser(id.toString(), name, null, List.of(role), null, department);
        }
    }

    private TestIdentityProvider() {
    }

    static String jwkSetUri() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/certs";
    }

    static String token(TestUser user) {
        return sign(user, AUDIENCE, KEY);
    }

    static String tokenForAudience(TestUser user, String audience) {
        return sign(user, audience, KEY);
    }

    /** Токен с правильными полями, но подписанный чужим ключом — подделка. */
    static String forgedToken(TestUser user) {
        return sign(user, AUDIENCE, generateKey("test-key"));
    }

    private static String sign(TestUser user, String audience, RSAKey key) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(audience)
                .subject(user.subject())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("name", user.name())
                .claim("email", user.email())
                .claim("roles", user.roles());
        if (user.counterpartyId() != null) {
            claims.claim("counterparty_id", user.counterpartyId().toString());
        }
        if (user.department() != null) {
            claims.claim("department", user.department());
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpServer startKeyServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
