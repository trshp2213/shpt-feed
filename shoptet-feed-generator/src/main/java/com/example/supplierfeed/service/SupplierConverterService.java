package com.example.supplierfeed.service;

import com.example.shoptetfeed.model.ShoptetItem;
import com.example.shoptetfeed.service.PriceUtils;
import com.example.supplierfeed.model.SupplierProduct;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * Zamienia pozycje dostawcy na ShoptetItem (ten sam model i writer co Carinio).
 *
 * Cena:   reguła Carinio - cena brutto w PLN / kurs (supplier.eur-rate, stały 4,2),
 *         zaokrąglona do najbliższych 10 eurocentów; źródło już w EUR: 1:1 z tym samym
 *         zaokrągleniem. Bez marży. PRICE = PRICE_VAT (writer).
 * Stan:   "Skladom" (writer wpisze 19) gdy dostępny, inaczej "Nedostupné" (0).
 * Kategoria: "<category-root> > <ścieżka dostawcy, każdy poziom po SK>".
 * Kod:    supplier.code-prefix + kod źródłowy, znormalizowany jak robi to Shoptet.
 */
@Slf4j
@Service
public class SupplierConverterService {

    @Value("${supplier.name:supplier}")
    private String name;

    @Value("${supplier.category-root}")
    private String categoryRoot;

    @Value("${supplier.code-prefix:}")
    private String codePrefix;

    /** Waluta cen w feedzie dostawcy: PLN (konwersja po kursie) albo EUR (1:1). */
    @Value("${supplier.source-currency:PLN}")
    private String sourceCurrency;

    @Value("${supplier.eur-rate:4.2}")
    private double eurRate;

    @Value("${supplier.currency:EUR}")
    private String currency;

    /** Gdy ustawione - producent zawsze taki (Kotto: pole marki w feedzie to kolor). */
    @Value("${supplier.fixed-manufacturer:}")
    private String fixedManufacturer;

    /** Producent, gdy w feedzie jest pusty. */
    @Value("${supplier.default-manufacturer:}")
    private String defaultManufacturer;

    /** poziom kategorii PL (lowercase) -> nazwa SK; z supplier-category-translations.json */
    private final Map<String, String> categoryOverrides = new HashMap<>();

    @PostConstruct
    void loadCategoryOverrides() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/supplier-category-translations.json")) {
            if (in == null) return;
            Map<String, String> raw = new ObjectMapper().readValue(in, new TypeReference<>() {});
            raw.forEach((k, v) -> categoryOverrides.put(k.trim().toLowerCase(Locale.ROOT), v));
        }
        log.info("Wczytano {} ręcznych tłumaczeń poziomów kategorii", categoryOverrides.size());
    }

    // ------------------------------------------------------------------ teksty do tłumaczenia

    /** Teksty PL, które muszą trafić do cache SK. Kolejność = priorytet przy ograniczonym budżecie. */
    public List<String> textsToTranslate(List<SupplierProduct> products) {
        Set<String> texts = new LinkedHashSet<>();
        for (SupplierProduct p : products) add(texts, p.getName());
        for (SupplierProduct p : products) {
            for (String seg : segments(p.getCategory())) {
                if (!categoryOverrides.containsKey(seg.toLowerCase(Locale.ROOT))) add(texts, seg);
            }
        }
        for (SupplierProduct p : products) add(texts, descriptionHtml(p));
        return new ArrayList<>(texts);
    }

    private static void add(Set<String> texts, String t) {
        if (t != null && !t.isBlank()) texts.add(t.trim());
    }

    // ------------------------------------------------------------------ konwersja

    public List<ShoptetItem> convert(List<SupplierProduct> products, Map<String, String> sk) {
        List<ShoptetItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> seenEans = new HashSet<>();
        int skipped = 0, untranslated = 0;

        for (SupplierProduct p : products) {
            List<String> errors = new ArrayList<>();
            if (p.getCode() == null || p.getCode().isBlank()) errors.add("brak kodu");
            if (p.getName() == null || p.getName().isBlank()) errors.add("brak nazwy");
            if (p.getMainImage() == null || p.getMainImage().isBlank()) errors.add("brak zdjęcia");
            if (p.getPrice() <= 0) errors.add("cena <= 0");
            String code = errors.isEmpty() ? shoptetCode(codePrefix + p.getCode()) : null;
            if (code != null && !seen.add(code)) errors.add("zduplikowany CODE " + code);
            if (!errors.isEmpty()) {
                log.error("[{}] Pozycja id={} code='{}' pominięta: {}", name, p.getExternalCode(), p.getCode(),
                        String.join("; ", errors));
                skipped++;
                continue;
            }

            String itemName = tr(p.getName(), sk);
            if (!sk.containsKey(p.getName().trim())) untranslated++;

            String desc = tr(descriptionHtml(p), sk);
            String shortDesc = firstSentence(desc);
            if (desc == null || desc.isBlank()) desc = itemName;

            String manufacturer = !fixedManufacturer.isBlank() ? fixedManufacturer
                    : (p.getBrand() == null || p.getBrand().isBlank() ? defaultManufacturer : p.getBrand());

            List<String> additional = p.getAdditionalImages() == null ? List.of() : p.getAdditionalImages();
            result.add(ShoptetItem.builder()
                    .code(code)
                    .externalCode(p.getExternalCode())
                    .productUrl(p.getUrl())
                    .name(itemName)
                    .description(desc)
                    .shortDescription(shortDesc)
                    .manufacturer(manufacturer.isBlank() ? null : manufacturer)
                    .category(category(p.getCategory(), sk))
                    .ean(uniqueEan(p, seenEans))
                    .price(price(p.getPrice()))
                    .currency(currency)
                    .availability(p.isAvailable() ? "Skladom" : "Nedostupné")
                    .weight(p.getWeight())
                    .widthCm(p.getWidthCm())
                    .lengthCm(p.getLengthCm())
                    .heightCm(p.getHeightCm())
                    .mainImage(p.getMainImage())
                    .additionalImages(additional)
                    .visibility("visible")
                    .build());
        }
        log.info("[{}] Skonwertowano {} pozycji, pominięto {}, {} nazw bez tłumaczenia SK",
                name, result.size(), skipped, untranslated);
        return result;
    }

    // ------------------------------------------------------------------ reguły

    /** EAN tylko dla pierwszej pozycji z danym EAN-em (dostawca bywa dubluje go między wariantami). */
    private String uniqueEan(SupplierProduct p, Set<String> seenEans) {
        String ean = blankToNull(p.getEan());
        if (ean == null) return null;
        if (seenEans.add(ean.trim())) return ean.trim();
        log.warn("[{}] EAN {} powtórzony (id={}) - pomijam EAN w tej pozycji", name, ean, p.getExternalCode());
        return null;
    }

    double price(double source) {
        return "EUR".equalsIgnoreCase(sourceCurrency)
                ? PriceUtils.roundToNearestTenCents(source)
                : PriceUtils.convertAndRound(source, eurRate);
    }

    String category(String plPath, Map<String, String> sk) {
        List<String> out = new ArrayList<>();
        for (String seg : segments(plPath)) {
            String override = categoryOverrides.get(seg.toLowerCase(Locale.ROOT));
            String translated = override != null ? override : sk.getOrDefault(seg, seg);
            out.add(isAllCaps(seg) && override == null ? sentenceCase(translated) : translated);
        }
        return out.isEmpty() ? categoryRoot : categoryRoot + " > " + String.join(" > ", out);
    }

    private static List<String> segments(String path) {
        List<String> list = new ArrayList<>();
        if (path == null) return list;
        for (String s : path.split(">")) {
            if (!s.isBlank()) list.add(s.trim());
        }
        return list;
    }

    private static boolean isAllCaps(String s) {
        return s.chars().anyMatch(Character::isLetter) && s.equals(s.toUpperCase(Locale.ROOT));
    }

    /** "DEKY PRE ZVIERATÁ" -> "Deky pre zvieratá" (jak w Carinio). */
    private static String sentenceCase(String s) {
        if (s == null || s.isBlank()) return s;
        String lower = s.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Opis do tłumaczenia/wysyłki: zwykły tekst z łamaniami linii -> akapity HTML. */
    static String descriptionHtml(SupplierProduct p) {
        String d = p.getDescription();
        if (d == null || d.isBlank()) return null;
        d = d.trim();
        if (d.contains("<")) return d;                       // już HTML (Kotto)
        StringBuilder sb = new StringBuilder();
        for (String line : d.split("\\R+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            sb.append("<p>").append(t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</p>");
        }
        return sb.toString();
    }

    /** Pierwsze zdanie opisu (po zdjęciu HTML) jako krótki opis - jak w Carinio, bez kosztu tłumaczenia. */
    static String firstSentence(String html) {
        if (html == null || html.isBlank()) return null;
        String t = html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        int end = t.indexOf(". ", 60);
        if (end > 0 && end <= 300) return t.substring(0, end + 1);
        if (t.length() <= 300) return t;
        int cut = t.lastIndexOf(' ', 300);
        return t.substring(0, cut > 0 ? cut : 300) + "…";
    }

    private static String tr(String text, Map<String, String> sk) {
        if (text == null || text.isBlank()) return text;
        return sk.getOrDefault(text.trim(), text);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Kod w postaci, w jakiej zapisuje go Shoptet (WIELKIE LITERY, bez diakrytyków, '+' i długie myślniki -> '-'). */
    static String shoptetCode(String code) {
        String s = code.trim().replace('ł', 'l').replace('Ł', 'L');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.replace('+', '-').replace('–', '-').replace('—', '-').toUpperCase(Locale.ROOT);
    }
}
