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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kursy średnie z tabeli A NBP dla wielu walut.
 * Free, no API key required. Docs: https://api.nbp.pl/
 *
 * Zwracana mapa: kod waluty → ile PLN kosztuje 1 jednostka waluty.
 * Konwersja: price_x = price_pln / rate.
 *
 * UWAGA: drukowana tabela A pokazuje HUF za 100 jednostek, ale API zwraca
 * pole `mid` ZAWSZE znormalizowane do 1 jednostki (np. HUF mid ≈ 0.0118).
 * Nie wolno tu niczego dzielić – wcześniejsze dzielenie przez 100 zawyżało
 * ceny HUF stukrotnie.
 */
@Slf4j
@Service
public class NbpCurrencyService {

    private static final String NBP_URL_TEMPLATE = "https://api.nbp.pl/api/exchangerates/rates/a/%s/?format=json";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Pobiera kursy dla podanych kodów walut (np. [EUR, CZK, HUF, RON]).
     */
    public Map<String, Double> getRates(List<String> currencyCodes) throws Exception {
        Map<String, Double> rates = new LinkedHashMap<>();
        for (String code : currencyCodes) {
            rates.put(code.toUpperCase(Locale.ROOT), fetchRate(code));
        }
        return rates;
    }

    /** Zachowana dla zgodności: kurs PLN/EUR (ile PLN = 1 EUR). */
    public double getPlnToEurRate() throws Exception {
        return fetchRate("EUR");
    }

    private double fetchRate(String code) throws Exception {
        String url = String.format(NBP_URL_TEMPLATE, code.toLowerCase(Locale.ROOT));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("NBP API returned HTTP " + response.statusCode() + " for " + code);
        }

        JsonNode root = objectMapper.readTree(response.body());
        double rate = root.get("rates").get(0).get("mid").asDouble();

        log.info("NBP rate: 1 {} = {} PLN", code.toUpperCase(Locale.ROOT), rate);
        return rate;
    }
}
