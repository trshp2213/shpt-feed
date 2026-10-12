package com.example.tuptifeed.service;

import com.example.shoptetfeed.model.ShoptetItem;
import com.example.tuptifeed.model.TuptiProduct;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.*;

/**
 * Zamienia pozycje dostawcy na ShoptetItem (ten sam model i writer co Carinio).
 *
 * Cena:   price_net x tupti.price-multiplier, zaokrąglone W GÓRĘ do 5 centów
 *         (x,x1-x,x4 -> x,x5; x,x6-x,x9 -> następne ,x0). PRICE = PRICE_VAT.
 * Stan:   "Skladom" (writer wpisze 19) gdy status = dostępny ORAZ store > 0, inaczej "Nedostupné" (0).
 * Kategoria: "TUPTIWOOD > <ścieżka z feedu, każdy poziom przetłumaczony na SK>".
 */
@Slf4j
@Service
public class TuptiConverterService {

    private static final BigDecimal FIVE_CENTS = new BigDecimal("0.05");

    @Value("${tupti.price-multiplier:2}")
    private BigDecimal priceMultiplier;

    @Value("${tupti.currency:EUR}")
    private String currency;

    @Value("${tupti.category-root:TUPTIWOOD}")
    private String categoryRoot;

    @Value("${tupti.default-manufacturer:Tuptiwood}")
    private String defaultManufacturer;

    /** false = nie wysyłamy wymiarów opakowania (w feedzie dostawcy jest ich tylko kilka powtarzalnych zestawów). */
    @Value("${tupti.send-dimensions:true}")
    private boolean sendDimensions;

    /** poziom kategorii PL (lowercase) -> nazwa SK; ładowane z tupti-category-translations.json */
    private Map<String, String> categoryOverrides = new HashMap<>();

    @PostConstruct
    void loadCategoryOverrides() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tupti-category-translations.json")) {
            if (in == null) return;
            Map<String, String> raw = new ObjectMapper().readValue(in, new TypeReference<>() {});
            raw.forEach((k, v) -> categoryOverrides.put(k.trim().toLowerCase(Locale.ROOT), v));
        }
        log.info("Wczytano {}
