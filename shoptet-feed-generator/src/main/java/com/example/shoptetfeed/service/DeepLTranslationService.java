package com.example.shoptetfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class DeepLTranslationService {

    @Value("${deepl.api-key}")
    private String apiKey;

    @Value("${deepl.api-url}")
    private String apiUrl;

    // DeepL free tier: max 50 texts per request
    private static final int BATCH_SIZE = 50;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Translates all texts not already in the cache.
     * Updates the cache in-place and returns it.
     */
    public Map<String, String> translateAll(List<String> texts, Map<String, String> cache) throws Exception {
        // Collect only texts missing from cache (deduplicated)
        List<String> toTranslate = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .filter(t -> !cache.containsKey(t))
                .toList();

        if (toTranslate.isEmpty()) {
            log.info("All {} texts already cached, no DeepL calls needed", texts.size());
            return cache;
        }

        log.info("Sending {} new texts to DeepL (in batches of {})", toTranslate.size(), BATCH_SIZE);

        // Split into batches
        for (int i = 0; i < toTranslate.size(); i += BATCH_SIZE) {
            List<String> batch = toTranslate.subList(i, Math.min(i + BATCH_SIZE, toTranslate.size()));
            Map<String, String> batchResult = translateBatch(batch);
            cache.putAll(batchResult);
        }

        return cache;
    }

    private Map<String, String> translateBatch(List<String> texts) throws Exception {
        // Build JSON request body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", texts);
        body.put("source_lang", "PL");
        body.put("target_lang", "SK");
        body.put("tag_handling", "html"); // preserve HTML tags inside descriptions

        String jsonBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "DeepL-Auth-Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("DeepL API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode translations = root.get("translations");

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            String translated = translations.get(i).get("text").asText();
            result.put(texts.get(i), translated);
        }

        log.info("DeepL translated {} texts successfully", texts.size());
        return result;
    }

    /**
     * Convenience method: translate a single text using the cache.
     * Returns the original text if translation is unavailable.
     */
    public String translate(String text, Map<String, String> cache) {
        if (text == null || text.isBlank()) return text;
        return cache.getOrDefault(text, text);
    }
}
