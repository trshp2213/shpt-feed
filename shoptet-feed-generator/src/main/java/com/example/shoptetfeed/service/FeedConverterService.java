package com.example.shoptetfeed.service;

import com.example.shoptetfeed.model.EuroCartProduct;
import com.example.shoptetfeed.model.ShoptetItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedConverterService {

    private final DeepLTranslationService translationService;

    @Value("${shoptet.parent-category}")
    private String parentCategory;

    @Value("${shoptet.currency}")
    private String currency;

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
        String translatedName     = translationService.translate(p.getName(), cache);
        String translatedDesc     = translationService.translate(p.getDescription(), cache);
        String translatedCategory = translationService.translate(p.getCategory(), cache);

        String fullCategory = translatedCategory.isBlank()
                ? parentCategory
                : parentCategory + " > " + translatedCategory;

        double priceEur = PriceUtils.convertAndRound(p.getPrice(), eurRate);

        // Mapowanie dostępności PL → SK
        String availability = mapAvailability(p.getAvailabilityText());

        String manufacturer = p.getBrand().isBlank() ? parentCategory : p.getBrand();

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
     * Mapuje polskie teksty dostępności na słowackie odpowiedniki.
     * Jeżeli wartość nieznana lub nieobsługiwana – zwraca null (nie wpisujemy domyślnej wartości).
     */
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

    /**
     * Walidacja skonwertowanego produktu.
     * Loguje błędy i zwraca false jeśli produkt nie powinien trafić do XML.
     */
    private boolean validate(ShoptetItem item, EuroCartProduct source) {
        List<String> errors = new ArrayList<>();

        if (item.getName() == null || item.getName().isBlank())
            errors.add("brak nazwy (source name='" + source.getName() + "')");

        if (item.getCode() == null || item.getCode().isBlank())
            errors.add("brak kodu produktu");

        if (item.getPrice() <= 0)
            errors.add("cena <= 0 (source price=" + source.getPrice() + " PLN, rate wymagane)");

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
            log.error("Produkt id={} code={} pominięty – błędy walidacji: {}",
                    source.getId(), source.getCode(), String.join("; ", errors));
            return false;
        }

        return true;
    }
}
