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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider Microsoft Azure Translator (tier F0 – 2M znaków/miesiąc za darmo).
 * API v3: POST {endpoint}/translate?api-version=3.0&from=pl&to=cs
 * Body: [{"Text": "..."}, ...]
 * Auth: nagłówki Ocp-Apim-Subscription-Key + Ocp-Apim-Subscription-Region.
 */
@Slf4j
@Service
public class AzureTranslatorProvider implements TranslationProvider {

    @Value("${azure.api-key:}")
    private String apiKey;

    @Value("${azure.region:}")
    private String region;

    @Value("${azure.endpoint}")
    private String endpoint;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "azure";
    }

    @Override
    public int maxBatchTexts() {
        return 50; // limit Azure: max 1000 elementów, trzymamy się nisko dla bezpieczeństwa
    }

    @Override
    public int maxBatchChars() {
        return 40_000; // limit Azure: 50k znaków per request – zostawiamy margines
    }

    @Override
    public Map<String, String> translateBatch(List<String> texts, String sourceLang, String targetLang) throws Exception {
        List<Map<String, String>> body = new ArrayList<>();
        for (String t : texts) {
            body.add(Map.of("Text", t));
        }
        String jsonBody = objectMapper.writeValueAsString(body);

        String url = endpoint + "/translate?api-version=3.0"
                + "&from=" + sourceLang
                + "&to=" + targetLang
                + "&textType=html"; // zachowuje ewentualne tagi HTML w opisach

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .header("Ocp-Apim-Subscription-Region", region)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("Azure Translator returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            result.put(texts.get(i), root.get(i).get("translations").get(0).get("text").asText());
        }

        log.info("Azure translated {} texts to {}", texts.size(), targetLang);
        return result;
    }
}
