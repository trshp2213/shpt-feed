package com.example.shoptetfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches the current PLN/EUR exchange rate from NBP (Polish National Bank).
 * Free, no API key required.
 * API docs: https://api.nbp.pl/
 */
@Slf4j
@Service
public class NbpCurrencyService {

    private static final String NBP_URL = "https://api.nbp.pl/api/exchangerates/rates/a/eur/?format=json";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the PLN/EUR mid rate (how many PLN = 1 EUR).
     * To convert PLN to EUR: price_eur = price_pln / rate
     */
    public double getPlnToEurRate() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NBP_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("NBP API returned HTTP " + response.statusCode());
        }

        // Response: {"rates": [{"mid": 4.2850}]}
        JsonNode root = objectMapper.readTree(response.body());
        double rate = root.get("rates").get(0).get("mid").asDouble();

        log.info("Current PLN/EUR rate from NBP: {} PLN = 1 EUR", rate);
        return rate;
    }
}
