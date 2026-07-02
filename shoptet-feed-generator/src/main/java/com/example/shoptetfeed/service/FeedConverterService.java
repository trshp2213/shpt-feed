package com.example.shoptetfeed.service;

import com.example.shoptetfeed.model.EuroCartProduct;
import com.example.shoptetfeed.model.ShoptetItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedConverterService {

    private final DeepLTranslationService translationService;

    @Value("${shoptet.parent-category}")
    private String parentCategory; // "Carinio"

    @Value("${shoptet.currency}")
    private String currency; // "EUR"

    /**
     * Converts euro-cart products into Shoptet items.
     *
     * @param products  raw products from euro-cart feed
     * @param cache     PL→SK translation cache (already populated)
     * @param eurRate   current PLN/EUR rate from NBP
     */
    public List<ShoptetItem> convert(List<EuroCartProduct> products, Map<String, String> cache, double eurRate) {
        return products.stream()
                .map(p -> convertOne(p, cache, eurRate))
                .toList();
    }

    private ShoptetItem convertOne(EuroCartProduct p, Map<String, String> cache, double eurRate) {
        String translatedName = translationService.translate(p.getName(), cache);
        String translatedDesc = translationService.translate(p.getDescription(), cache);
        String translatedCategory = translationService.translate(p.getCategory(), cache);

        // Build category path: "Carinio > Dečky"
        String fullCategory = translatedCategory.isBlank()
                ? parentCategory
                : parentCategory + " > " + translatedCategory;

        double priceEur = PriceUtils.convertAndRound(p.getPrice(), eurRate);

        String availability = p.getStock() > 0 ? "Skladom" : "Nedostupné";

        return ShoptetItem.builder()
                .code(p.getCode())
                .externalCode(p.getId())
                .name(translatedName)
                .description(translatedDesc)
                .manufacturer(p.getBrand().isBlank() ? parentCategory : p.getBrand())
                .category(fullCategory)
                .ean(p.getEan())
                .price(priceEur)
                .currency(currency)
                .stockCount(p.getStock())
                .availability(availability)
                .weight(p.getWeight())
                .mainImage(p.getMainImage())
                .additionalImages(p.getAdditionalImages())
                .visibility("visible")
                .build();
    }
}
