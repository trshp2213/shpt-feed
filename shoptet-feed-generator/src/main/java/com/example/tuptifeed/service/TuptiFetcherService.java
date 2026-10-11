package com.example.tuptifeed.service;

import com.example.tuptifeed.model.TuptiProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Pobiera products.xml (pełne dane) i basic.xml (stan + cena, odświeżany co godzinę)
 * dostawcy tupti.wood i składa z nich płaską listę pozycji do feedu.
 *
 * - basic.xml nadpisuje cenę i stan z products.xml (klucz: id + variant_id).
 * - Produkt z wariantami jest rozbity na osobne pozycje (po jednej na wariant),
 *   rodzic jest pomijany.
 * - Odczyt pól tylko z BEZPOŚREDNICH dzieci elementu, bo warianty mają własne
 *   tagi price_net/store/status/weight zagnieżdżone w <variants>.
 */
@Slf4j
@Service
public class TuptiFetcherService {

    @Value("${tupti.products-url}")
    private String productsUrl;

    @Value("${tupti.basic-url}")
    private String basicUrl;

    /** id produktów pomijanych w całości (np. pakiety hotelowe). */
    @Value("${tupti.excluded-ids:}")
    private List<String> excludedIds;

    private record Overlay(String priceNet, String store) {}

    public List<TuptiProduct> fetch() throws Exception {
        Document productsDoc = parse(download(productsUrl));
        Document basicDoc = parse(download(basicUrl));

        Map<String, Overlay> overlay = readBasic(basicDoc);
        log.info("basic.xml: {} wierszy (cena + stan)", overlay.size());

        Set<String> excluded = new HashSet<>();
        for (String id : excludedIds) {
            if (!id.isBlank()) excluded.add(id.trim());
        }

        List<TuptiProduct> result = new ArrayList<>();
        int parents = 0, missingOverlay = 0;
        NodeList nodes = productsDoc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element p) || !"product".equals(p.getTagName())) continue;
            String id = text(p, "id");
            if (excluded.contains(id)) {
                log.info("Pominięto produkt id={} (tupti.excluded-ids)", id);
                continue;
            }

            List<Element> variants = new ArrayList<>();
            Element variantsEl = child(p, "variants");
            if (variantsEl != null) {
                for (Element v : children(variantsEl, "variant")) variants.add(v);
            }

            // Dane wspólne (rodzic)
            List<String> images = new ArrayList<>();
            Element gallery = child(p, "gallery");
            if (gallery != null) {
                for (Element img : children(gallery, "image")) {
                    String url = encodeUrl(img.getTextContent().trim());
                    if (!url.isBlank()) images.add(url);
                }
            }
            double width = 0, length = 0, height = 0;
            String age = null;
            Element dicts = child(p, "dictionaries");
            if (dicts != null) {
                for (Element d : children(dicts, "dictionary")) {
                    String name = text(d, "name");
                    Element words = child(d, "words");
                    String word = null;
                    if (words != null) {
                        Element w = child(words, "word");
                        if (w != null) word = w.getTextContent().trim();
                    }
                    if (word == null || word.isBlank()) continue;
                    switch (name) {
                        case "Długość opakowania" -> length = num(word);
                        case "Szerokość opakowania" -> width = num(word);
                        case "Wysokość opakowania" -> height = num(word);
                        case "Wiek dziecka" -> age = word;
                        default -> { }
                    }
                }
            }

            TuptiProduct.TuptiProductBuilder base = TuptiProduct.builder()
                    .name(text(p, "name"))
                    .shortDescription(text(p, "desc_short"))
                    .description(text(p, "desc_long"))
                    .safety(text(p, "desc_safety"))
                    .category(text(p, "category"))
                    .producer(text(p, "producer"))
                    .widthCm(width).lengthCm(length).heightCm(height)
                    .age(age)
                    .images(images);

            if (variants.isEmpty()) {
                Overlay o = overlay.get(id + "|");
                if (o == null) missingOverlay++;
                result.add(base
                        .code(text(p, "symbol"))
                        .externalCode(id)
                        .ean(text(p, "ean"))
                        .priceNet(o != null ? o.priceNet() : text(p, "price_net"))
                        .store(parseStore(o != null ? o.store() : text(p, "store")))
                        .statusAvailable("dostępny".equalsIgnoreCase(text(p, "status")))
                        .weight(num(text(p, "weight")))
                        .build());
            } else {
                parents++;
                double parentWeight = num(text(p, "weight"));
                for (Element v : variants) {
                    String variantId = text(v, "id");
                    Overlay o = overlay.get(id + "|" + variantId);
                    if (o == null) missingOverlay++;
                    double w = num(text(v, "weight"));
                    result.add(base
                            .code(text(v, "symbol"))
                            .externalCode(id + "-" + variantId)
                            .ean(text(v, "ean"))
                            .variantLabel(variantLabel(v))
                            .priceNet(o != null ? o.priceNet() : text(v, "price_net"))
                            .store(parseStore(o != null ? o.store() : text(v, "store")))
                            .statusAvailable("dostępny".equalsIgnoreCase(text(v, "status")))
                            .weight(w > 0 ? w : parentWeight)
                            .build());
                }
            }
        }
        log.info("products.xml: {} pozycji do feedu ({} produktów z wariantami rozbitych na warianty, "
                + "{} bez wiersza w basic.xml - użyto danych z products.xml)", result.size(), parents, missingOverlay);
        return result;
    }

    /** "Kolor"/"Wybierz wariant" -> sama wartość; inne parametry -> "Nazwa: wartość". */
    private String variantLabel(Element variant) {
        Element items = child(variant, "items");
        if (items == null) return null;
        List<String> parts = new ArrayList<>();
        for (Element item : children(items, "item")) {
            String name = text(item, "name");
            String value = text(item, "value");
            if (value.isBlank()) continue;
            parts.add(name.isBlank() || "Kolor".equalsIgnoreCase(name) || "Wybierz wariant".equalsIgnoreCase(name)
                    ? value : name + ": " + value);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private Map<String, Overlay> readBasic(Document doc) {
        Map<String, Overlay> map = new HashMap<>();
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element p) || !"product".equals(p.getTagName())) continue;
            map.put(text(p, "id") + "|" + text(p, "variant_id"), new Overlay(text(p, "price_net"), text(p, "store")));
        }
        return map;
    }

    // ------------------------------------------------------------------ helpers

    private String download(String url) throws Exception {
        log.info("Pobieram: {}", url.replaceAll("/xml/[^/]+/", "/xml/***/")); // token nie trafia do logów
        URLConnection c = URI.create(url).toURL().openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setRequestProperty("User-Agent", "shpt-feed/1.0");
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();
        return doc;
    }

    private static Element child(Element parent, String tag) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && tag.equals(e.getTagName())) list.add(e);
        }
        return list;
    }

    /** Treść bezpośredniego dziecka (pusty string, gdy brak). */
    private static String text(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? "" : e.getTextContent().trim();
    }

    /** Spacje i znaki spoza ASCII w adresie obrazka (np. "5225 (1).jpg") -> %XX, żeby Shoptet mógł je pobrać. */
    static String encodeUrl(String url) {
        StringBuilder sb = new StringBuilder();
        for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
            if (b == ' ' || b < 0) sb.append('%').append(String.format("%02X", b & 0xFF));
            else sb.append((char) b);
        }
        return sb.toString();
    }

    private static double num(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int parseStore(String s) {
        return (int) Math.max(0, Math.round(num(s)));
    }
}
