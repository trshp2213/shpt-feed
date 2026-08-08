package com.example.shoptetfeed.service;

import com.example.shoptetfeed.model.EuroCartProduct;
import com.example.shoptetfeed.model.ShoptetItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FeedConverterService {

    @Value("${shoptet.parent-category}")
    private String parentCategory;

    @Value("${shoptet.currency}")
    private String currency;

    /**
     * Mapa nadpisania prekladov kategorii.
     * Kluc = co pride z DeepL (porovnava sa case-insensitive)
     * Hodnota = spravny slovensky preklad
     */
    @Value("#{${shoptet.category-overrides:{}}}")
    private Map<String, String> categoryOverrides;

    public List<ShoptetItem> convert(List<EuroCartProduct> products, Map<String, String> cache, double eurRate) {
        List<ShoptetItem> result = new ArrayList<>();
        int skipped = 0;

        for (EuroCartProduct p : products) {
            try {
                ShoptetItem item = convertOne(p, cache, eurRate);
                if (validate(item, p)) {
                    result.add(item);
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Error converting product id={} code={}: {}", p.getId(), p.getCode(), e.getMessage());
                skipped++;
            }
        }

        if (skipped > 0) {
            log.warn("{} products skipped due to validation errors", skipped);
        }
        log.info("Converted {} products successfully", result.size());
        return result;
    }

    private ShoptetItem convertOne(EuroCartProduct p, Map<String, String> cache, double eurRate) {
        String translatedName     = translate(p.getName(), cache);
        String translatedDesc     = translate(p.getDescription(), cache);
        String translatedCategory = translate(p.getCategory(), cache);

        // Aplikuj override ak existuje, inak normalizuj na Title Case
        String finalCategory = resolveCategory(p.getCategory(), translatedCategory);

        // Struktura: "Domáce zvieratá | Deky pre zvieratá"
        String fullCategory = finalCategory.isBlank()
                ? parentCategory
                : parentCategory + " > " + finalCategory;

        double priceEur = PriceUtils.convertAndRound(p.getPrice(), eurRate);

        String availability = mapAvailability(p.getAvailabilityText());
        String manufacturer = p.getBrand().isBlank() ? "Carinio" : p.getBrand();

        return ShoptetItem.builder()
                .code(p.getCode())
                .externalCode(p.getId())
                .productUrl(p.getUrl())
                .name(translatedName)
                .description(translatedDesc)
                .manufacturer(manufacturer)
                .category(fullCategory)
                .ean(p.getEan())
                .price(priceEur)
                .currency(currency)
                .availability(availability)
                .weight(p.getWeight())
                .mainImage(p.getMainImage())
                .additionalImages(p.getAdditionalImages())
                .visibility("visible")
                .build();
    }

    /**
     * Rozhodne o finalnom nazve kategorie:
     * 1. Ak existuje override pre originalny polsky nazov → pouzij ho
     * 2. Ak existuje override pre prelozeny nazov (case-insensitive) → pouzij ho
     * 3. Inak normalizuj preklad na Title Case (nie ALL CAPS)
     */
    private String resolveCategory(String originalPolish, String translated) {
        if (categoryOverrides != null) {
            // Skus najst override pre originalny polsky nazov
            for (Map.Entry<String, String> entry : categoryOverrides.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(originalPolish.trim())) {
                    log.debug("Category override applied: '{}' → '{}'", originalPolish, entry.getValue());
                    return entry.getValue();
                }
            }
            // Skus najst override pre prelozeny nazov
            for (Map.Entry<String, String> entry : categoryOverrides.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(translated.trim())) {
                    log.debug("Category override applied (translated match): '{}' → '{}'", translated, entry.getValue());
                    return entry.getValue();
                }
            }
        }
        // Normalizuj ALL CAPS na Title Case
        return toTitleCase(translated);
    }

    /**
     * Konvertuje "DEKY PRE ZVIERATÁ" na "Deky pre zvieratá"
     */
    private String toTitleCase(String input) {
        if (input == null || input.isBlank()) return input;
        String lower = input.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Zwraca tłumaczenie z cache albo oryginał, gdy tłumaczenia (jeszcze) brak. */
    private String translate(String text, Map<String, String> cache) {
        if (text == null || text.isBlank()) return text;
        return cache.getOrDefault(text, text);
    }

    private String mapAvailability(String availabilityText) {
        if (availabilityText == null || availabilityText.isBlank()) return null;
        return switch (availabilityText.toLowerCase().trim()) {
            case "dostępny", "dostepny", "dostępne", "w magazynie", "na stanie" -> "Skladom";
            case "niedostępny", "niedostepny", "niedostępne", "brak", "wyprzedany" -> "Nedostupné";
            default -> {
                log.warn("Nieznana wartość dostępności: '{}' – pomijam pole", availabilityText);
                yield null;
            }
        };
    }

    private boolean validate(ShoptetItem item, EuroCartProduct source) {
        List<String> errors = new ArrayList<>();

        if (item.getName() == null || item.getName().isBlank())
            errors.add("brak nazwy (source name='" + source.getName() + "')");
        if (item.getCode() == null || item.getCode().isBlank())
            errors.add("brak kodu produktu");
        if (item.getPrice() <= 0)
            errors.add("cena <= 0 (source price=" + source.getPrice() + " PLN)");
        if (item.getManufacturer() == null || item.getManufacturer().isBlank())
            errors.add("brak producenta");
        if (item.getMainImage() == null || item.getMainImage().isBlank())
            errors.add("brak zdjęcia");
        if (item.getDescription() == null || item.getDescription().isBlank())
            errors.add("brak opisu");
        if (source.getEan() != null && !source.getEan().isBlank()
                && (item.getEan() == null || item.getEan().isBlank()))
            errors.add("EAN był w źródle ale nie został przeniesiony");

        if (!errors.isEmpty()) {
            log.error("Produkt id={} code={} pominięty – błędy: {}",
                    source.getId(), source.getCode(), String.join("; ", errors));
            return false;
        }
        return true;
    }
}
