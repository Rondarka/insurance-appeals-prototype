package ru.mtuci.appeals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Локальная модель для тестов: OpenAI-совместимый /v1/chat/completions, как у Ollama.
 * Отвечает предсказуемо: на вопрос о претензии JSON в ```-блоке (так часто отвечают
 * настоящие модели), на просьбу о черновике текст. Её можно «выключить», тогда она отвечает 503.
 */
final class TestLocalModel {

    static final String DRAFT_PREFIX = "Черновик модели. ";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile boolean available = true;
    private static final HttpServer SERVER = start();

    private TestLocalModel() {
    }

    static String baseUrl() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/v1";
    }

    static void available(boolean value) {
        available = value;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!available) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                String content = request.contains("\\\"pretension\\\"")
                        ? "```json\n{\"pretension\": " + request.contains("доплат") + ", \"reason\": \"требую доплатить\"}\n```"
                        : DRAFT_PREFIX + "Итог учтён, данные договора сохранены.";
                byte[] body = JSON.writeValueAsBytes(Map.of("choices",
                        List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
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
