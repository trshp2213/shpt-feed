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
import java.util.*;

/**
 * Zamienia pozycje dostawcy na ShoptetItem (ten sam model i writer co Carinio).
 *
 * Cena:   price_net x tupti.price-multiplier, zaokrąglone W GÓRĘ do 5 centów
 *         (x,x1-x,x4 -> x,x5; x,x6-x,x9 -> następne ,x0). PRICE = PRICE_VAT.
 * Stan:   "Skladom" (writer wpisze 19) gdy status = dostępny ORAZ store > 0, inaczej "Nedostupné" (0).
 * Kategoria: "Tuptiwood > <ścieżka z feedu, każdy poziom przetłumaczony na SK>".
 */
@Slf4j
@Service
public class TuptiConverterService {

    private static final BigDecimal FIVE_CENTS = new BigDecimal("0.05");

    @Value("${tupti.price-multiplier:2}")
    private BigDecimal priceMultiplier;

    @Value("${tupti.currency:EUR}")
    private String currency;

    @Value("${tupti.category-root:Tuptiwood}")
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
        log.info("Wczytano {} ręcznych tłumaczeń poziomów kategorii", categoryOverrides.size());
    }

    // ------------------------------------------------------------------ teksty do tłumaczenia

    /** Wszystkie teksty PL, które trzeba mieć w cache SK. Kolejność = priorytet przy ograniczonym budżecie. */
    public List<String> textsToTranslate(List<TuptiProduct> products) {
        Set<String> texts = new LinkedHashSet<>();
        for (TuptiProduct p : products) {
            add(texts, p.getName());
            add(texts, p.getVariantLabel());
        }
        for (TuptiProduct p : products) {
            for (String segment : categorySegments(p.getCategory())) {
                if (!categoryOverrides.containsKey(segment.toLowerCase(Locale.ROOT))) add(texts, segment);
            }
        }
        for (TuptiProduct p : products) {
            add(texts, p.getShortDescription());
            add(texts, p.getDescription());
            add(texts, formatSafety(p.getSafety()));
        }
        return new ArrayList<>(texts);
    }

    private static void add(Set<String> texts, String t) {
        if (t != null && !t.isBlank()) texts.add(t.trim());
    }

    // ------------------------------------------------------------------ konwersja

    public List<ShoptetItem> convert(List<TuptiProduct> products, Map<String, String> sk) {
        List<ShoptetItem> result = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        int skipped = 0, untranslated = 0;

        for (TuptiProduct p : products) {
            List<String> errors = new ArrayList<>();
            if (p.getCode() == null || p.getCode().isBlank()) errors.add("brak symbolu (CODE)");
            if (p.getName() == null || p.getName().isBlank()) errors.add("brak nazwy");
            if (p.getImages() == null || p.getImages().isEmpty()) errors.add("brak zdjęcia");
            double price = 0;
            try {
                price = price(p.getPriceNet());
            } catch (Exception e) {
                errors.add("nieprawidłowa cena '" + p.getPriceNet() + "'");
            }
            if (errors.isEmpty() && price <= 0) errors.add("cena <= 0");
            if (errors.isEmpty() && !seenCodes.add(p.getCode())) errors.add("zduplikowany CODE");
            if (!errors.isEmpty()) {
                log.error("Pozycja externalCode={} code='{}' pominięta: {}", p.getExternalCode(), p.getCode(),
                        String.join("; ", errors));
                skipped++;
                continue;
            }

            String name = tr(p.getName(), sk);
            if (p.getVariantLabel() != null && !p.getVariantLabel().isBlank()) {
                name = name + " – " + tr(p.getVariantLabel(), sk);
            }
            if (!sk.containsKey(p.getName().trim())) untranslated++;

            String shortDesc = blankToNull(tr(p.getShortDescription(), sk));
            StringBuilder desc = new StringBuilder();
            String longDesc = tr(p.getDescription(), sk);
            if (longDesc != null && !longDesc.isBlank()) desc.append(longDesc);
            else if (shortDesc != null) desc.append(shortDesc);
            String safety = formatSafety(p.getSafety());
            if (safety != null) {
                desc.append("<h3>Bezpečnostné upozornenia</h3><p>").append(tr(safety, sk)).append("</p>");
            }
            if (p.getAge() != null && !p.getAge().isBlank()) {
                desc.append("<p><strong>Odporúčaný vek:</strong> ").append(p.getAge()).append("</p>");
            }
            if (desc.length() == 0) desc.append(name);

            List<String> images = p.getImages();
            result.add(ShoptetItem.builder()
                    .code(p.getCode())
                    .externalCode(p.getExternalCode())
                    .name(name)
                    .description(desc.toString())
                    .shortDescription(shortDesc)
                    .manufacturer(p.getProducer() == null || p.getProducer().isBlank()
                            ? defaultManufacturer : p.getProducer())
                    .category(category(p.getCategory(), sk))
                    .ean(blankToNull(p.getEan()))
                    .price(price)
                    .currency(currency)
                    .availability(p.isStatusAvailable() && p.getStore() > 0 ? "Skladom" : "Nedostupné")
                    .weight(p.getWeight())
                    .widthCm(sendDimensions ? p.getWidthCm() : 0)
                    .lengthCm(sendDimensions ? p.getLengthCm() : 0)
                    .heightCm(sendDimensions ? p.getHeightCm() : 0)
                    .mainImage(images.get(0))
                    .additionalImages(images.subList(1, images.size()))
                    .visibility("visible")
                    .build());
        }
        log.info("Skonwertowano {} pozycji, pominięto {}, {} nazw bez tłumaczenia SK (zostają po polsku do kolejnego runa)",
                result.size(), skipped, untranslated);
        return result;
    }

    // ------------------------------------------------------------------ reguły

    /** price_net x mnożnik, w górę do pełnych 5 centów. */
    BigDecimal priceRounded(String priceNet) {
        BigDecimal value = new BigDecimal(priceNet.trim().replace(',', '.')).multiply(priceMultiplier);
        return value.divide(FIVE_CENTS, 0, RoundingMode.CEILING).multiply(FIVE_CENTS).setScale(2, RoundingMode.HALF_UP);
    }

    private double price(String priceNet) {
        return priceRounded(priceNet).doubleValue();
    }

    String category(String plPath, Map<String, String> sk) {
        List<String> segments = new ArrayList<>();
        for (String segment : categorySegments(plPath)) {
            String override = categoryOverrides.get(segment.toLowerCase(Locale.ROOT));
            segments.add(override != null ? override : sk.getOrDefault(segment, segment));
        }
        return segments.isEmpty() ? categoryRoot : categoryRoot + " > " + String.join(" > ", segments);
    }

    private static List<String> categorySegments(String plPath) {
        List<String> segments = new ArrayList<>();
        if (plPath == null) return segments;
        for (String s : plPath.split(">")) {
            if (!s.isBlank()) segments.add(s.trim());
        }
        return segments;
    }

    /** Uwagi bezpieczeństwa przychodzą jako sklejone zdania ("...dziecka.Używaj...") - rozdzielamy <br>. */
    static String formatSafety(String safety) {
        if (safety == null || safety.isBlank()) return null;
        return safety.trim().replaceAll("(?<=[.!?])(?=\\p{Lu})", "<br>");
    }

    private static String tr(String text, Map<String, String> sk) {
        if (text == null || text.isBlank()) return text;
        return sk.getOrDefault(text.trim(), text);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
