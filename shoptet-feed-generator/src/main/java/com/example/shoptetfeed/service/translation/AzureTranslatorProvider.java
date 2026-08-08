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
import java.util.Optional;

/**
 * Provider Microsoft Azure Translator (tier F0).
 *
 * Azure F0 ma DWA niezależne limity:
 *  - miesięczny budżet znaków (pilnowany wyżej, w MultiLangTranslationService)
 *  - throttling "per minute": wg dokumentacji Microsoftu ok. 33 300 znaków/min
 *    w oknie kroczącym ("2 mln znaków/godzinę, rozłożone równomiernie"). Wysłanie
 *    zbyt dużo w krótkim czasie kończy się HTTP 429 NIEZALEŻNIE od tego, jak
 *    daleko jesteśmy od limitu miesięcznego – i to jest częstszy przypadek
 *    w praktyce niż realne wyczerpanie 2M/mies.
 *
 * Dlatego provider sam pilnuje kroczącego okna 60s (SAFE_CHARS_PER_MINUTE,
 * z marginesem poniżej oficjalnego limitu) i dodatkowo ma retry z backoffem
 * na wypadek 429 mimo throttlingu (np. Azure liczy okno nieco inaczej niż my).
 */
@Slf4j
@Service
public class AzureTranslatorProvider implements TranslationProvider {

    /** Margines bezpieczeństwa poniżej oficjalnego limitu Azure (~33 300 znaków/min dla F0). */
    private static final long SAFE_CHARS_PER_MINUTE = 28_000;
    private static final long WINDOW_MS = 60_000;

    @Value("${azure.api-key:}")
    private String apiKey;

    @Value("${azure.region:}")
    private String region;

    @Value("${azure.endpoint}")
    private String endpoint;

    @Value("${azure.max-retries:5}")
    private int maxRetries;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long windowStartMs = 0;
    private long charsInWindow = 0;

    @Override
    public String name() {
        return "azure";
    }

    @Override
    public int maxBatchTexts() {
        return 30; // mniejsze batch'e = drobniejsza kontrola nad oknem 60s
    }

    @Override
    public int maxBatchChars() {
        return 20_000; // bezpiecznie poniżej SAFE_CHARS_PER_MINUTE w jednym batchu
    }

    @Override
    public synchronized Map<String, String> translateBatch(List<String> texts, String sourceLang, String targetLang) throws Exception {
        List<Map<String, String>> body = new ArrayList<>();
        int chars = 0;
        for (String t : texts) {
            body.add(Map.of("Text", t));
            chars += t.length();
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

        for (int attempt = 0; ; attempt++) {
            awaitBudget(chars);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                charsInWindow += chars;
                JsonNode root = objectMapper.readTree(response.body());
                Map<String, String> result = new LinkedHashMap<>();
                for (int i = 0; i < texts.size(); i++) {
                    result.put(texts.get(i), root.get(i).get("translations").get(0).get("text").asText());
                }
                log.info("Azure translated {} texts ({} chars) to {}", texts.size(), chars, targetLang);
                return result;
            }

            if (response.statusCode() == 429 && attempt < maxRetries) {
                long waitMs = retryAfterMillis(response).orElse(WINDOW_MS);
                log.warn("Azure rate-limited translating to {} (attempt {}/{}) – waiting {} ms before retry",
                        targetLang, attempt + 1, maxRetries, waitMs);
                Thread.sleep(waitMs);
                windowStartMs = 0; // wymuś reset naszego licznika po przeczekaniu
                continue;
            }

            throw new RuntimeException("Azure Translator returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    /** Czeka, jeśli trzeba, aż wysłanie kolejnych `chars` znaków zmieści się w kroczącym oknie 60s. */
    private void awaitBudget(int chars) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (windowStartMs == 0 || now - windowStartMs >= WINDOW_MS) {
            windowStartMs = now;
            charsInWindow = 0;
            return;
        }
        if (charsInWindow + chars > SAFE_CHARS_PER_MINUTE) {
            long waitMs = WINDOW_MS - (now - windowStartMs) + 200; // +200ms margines
            log.info("Azure per-minute budget would be exceeded ({}+{} > {}) – waiting {} ms",
                    charsInWindow, chars, SAFE_CHARS_PER_MINUTE, waitMs);
            Thread.sleep(waitMs);
            windowStartMs = System.currentTimeMillis();
            charsInWindow = 0;
        }
    }

    private Optional<Long> retryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim()) * 1000;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                });
    }
}
