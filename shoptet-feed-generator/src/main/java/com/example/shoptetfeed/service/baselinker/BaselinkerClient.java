package com.example.shoptetfeed.service.baselinker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Niskopoziomowy klient API BaseLinkera.
 *
 * API: POST na jeden endpoint (connector.php), token w nagłówku X-BLToken,
 * body form-urlencoded: method=<nazwa>&parameters=<json>.
 * Limit: 100 requestów/min – wymuszamy odstęp między wywołaniami,
 * żeby cron nigdy się o niego nie rozbił.
 */
@Slf4j
@Service
public class BaselinkerClient {

    @Value("${baselinker.token:}")
    private String token;

    @Value("${baselinker.api-url}")
    private String apiUrl;

    @Value("${baselinker.request-interval-ms:650}")
    private long requestIntervalMs;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long lastRequestAt = 0;

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    /**
     * Wywołuje metodę API. parameters może być mapą lub POJO – zostanie
     * zserializowane do JSON. Rzuca wyjątek, gdy status != SUCCESS.
     */
    public JsonNode call(String method, Object parameters) throws Exception {
        throttle();

        String parametersJson = objectMapper.writeValueAsString(parameters);
        String body = "method=" + URLEncoder.encode(method, StandardCharsets.UTF_8)
                + "&parameters=" + URLEncoder.encode(parametersJson, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(60))
                .header("X-BLToken", token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("BaseLinker API returned HTTP " + response.statusCode()
                    + " for method " + method);
        }

        JsonNode root = objectMapper.readTree(response.body());
        String status = root.path("status").asText("");
        if (!"SUCCESS".equals(status)) {
            throw new RuntimeException("BaseLinker method " + method + " failed: "
                    + root.path("error_code").asText("?") + " – "
                    + root.path("error_message").asText("(no message)"));
        }
        return root;
    }

    private void throttle() throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastRequestAt;
        if (elapsed < requestIntervalMs) {
            Thread.sleep(requestIntervalMs - elapsed);
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
