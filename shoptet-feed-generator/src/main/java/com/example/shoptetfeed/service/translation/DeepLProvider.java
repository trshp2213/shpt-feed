package com.example.shoptetfeed.service.translation;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider DeepL – ta sama logika co dotychczasowy DeepLTranslationService,
 * tylko z parametryzowanym językiem docelowym (SK z cache + CS jako nowy język).
 */
@Slf4j
@Service
public class DeepLProvider implements TranslationProvider {

    @Value("${deepl.api-key:}")
    private String apiKey;

    @Value("${deepl.api-url}")
    private String apiUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "deepl";
    }

    @Override
    public int maxBatchTexts() {
        return 50; // limit DeepL: max 50 tekstów per request
    }

    @Override
    public int maxBatchChars() {
        return 60_000; // bezpiecznie poniżej limitu 128 KiB na body requestu
    }

    @Override
    public Map<String, String> translateBatch(List<String> texts, String sourceLang, String targetLang) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", texts);
        body.put("source_lang", sourceLang.toUpperCase(Locale.ROOT));
        body.put("target_lang", targetLang.toUpperCase(Locale.ROOT));
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

        JsonNode translations = objectMapper.readTree(response.body()).get("translations");

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            result.put(texts.get(i), translations.get(i).get("text").asText());
        }

        log.info("DeepL translated {} texts to {}", texts.size(), targetLang);
        return result;
    }
}
