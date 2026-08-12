package com.example.shoptetfeed.service;

import com.example.shoptetfeed.model.EuroCartProduct;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class EuroCartFetcherService {

    @Value("${eurocart.feed-url}")
    private String feedUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public List<EuroCartProduct> fetchProducts() throws Exception {
        log.info("Fetching euro-cart XML from: {}", feedUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(feedUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("euro-cart returned HTTP " + response.statusCode());
        }

        String xml = response.body();
        log.info("Feed downloaded, size: {} bytes", xml.length());
        return parseXml(xml);
    }

    private List<EuroCartProduct> parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        List<EuroCartProduct> products = new ArrayList<>();
        NodeList offerNodes = doc.getElementsByTagName("o");

        for (int i = 0; i < offerNodes.getLength(); i++) {
            Node node = offerNodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element o = (Element) node;

            try {
                EuroCartProduct product = parseProduct(o);
                products.add(product);
            } catch (Exception e) {
                log.warn("Skipping product id={} due to parse error: {}", o.getAttribute("id"), e.getMessage());
            }
        }

        log.info("Parsed {} products from feed", products.size());
        return products;
    }

    private EuroCartProduct parseProduct(Element o) {
        // ── Atrybuty tagu <o> ──────────────────────────────────────────────
        String id       = attr(o, "id");
        String url      = attr(o, "url");
        String priceStr = attr(o, "price");
        String currency = attr(o, "currency");
        String weightStr = attr(o, "weight");

        double price  = priceStr.isBlank()  ? 0.0 : Double.parseDouble(priceStr);
        double weight = weightStr.isBlank() ? 0.0 : Double.parseDouble(weightStr);

        // Kod_towaru – fallback na id jeśli tag nie istnieje
        String code = tag(o, "Kod_towaru");
        if (code.isBlank()) code = id;

        // ── Tagi dzieci ────────────────────────────────────────────────────
        String name         = tag(o, "name");
        String description  = tag(o, "desc");
        String category     = tag(o, "cat");
        String brand        = tag(o, "brand");
        String availability = tag(o, "availability"); // np. "Dostępny"

        // ── Obrazy ────────────────────────────────────────────────────────
        String mainImage = null;
        List<String> additionalImages = new ArrayList<>();
        NodeList imgNodes = o.getElementsByTagName("imgs");
        if (imgNodes.getLength() > 0) {
            Element imgs = (Element) imgNodes.item(0);
            NodeList mainNodes = imgs.getElementsByTagName("main");
            if (mainNodes.getLength() > 0) {
                mainImage = ((Element) mainNodes.item(0)).getAttribute("url");
            }
            NodeList iNodes = imgs.getElementsByTagName("i");
            for (int j = 0; j < iNodes.getLength(); j++) {
                String imgUrl = ((Element) iNodes.item(j)).getAttribute("url");
                if (!imgUrl.isBlank()) additionalImages.add(imgUrl);
            }
        }

        // ── EAN z attrs ───────────────────────────────────────────────────
        String ean = null;
        NodeList attrNodes = o.getElementsByTagName("a");
        for (int j = 0; j < attrNodes.getLength(); j++) {
            Element a = (Element) attrNodes.item(j);
            if ("EAN".equals(a.getAttribute("name"))) {
                ean = a.getTextContent().trim();
                break;
            }
        }

        // ── Wymiary z opisu ───────────────────────────────────────────────
        // Feed nie ma strukturalnego pola wymiarów – są tylko w tekście opisu,
        // np. "wymiary: 65 x 100 cm" albo "wymiary 90 x 135". Parsujemy do
        // 3 liczb (szer x dł x wys); brak dopasowania = zera (pola pomijane).
        double[] dims = parseDimensions(description);

        return EuroCartProduct.builder()
                .id(id)
                .url(url)
                .code(code)
                .name(name)
                .description(description)
                .category(category)
                .brand(brand)
                .price(price)
                .currency(currency)
                .availabilityText(availability)
                .weight(weight)
                .mainImage(mainImage)
                .additionalImages(additionalImages)
                .ean(ean)
                .widthCm(dims[0])
                .lengthCm(dims[1])
                .heightCm(dims[2])
                .build();
    }

    // Wzorzec 1: "wymiary [...] 58cm x 82cm x 107cm" / "wymiary: 65 x 100 cm".
    // [^\d]{0,60}? pozwala na dowolną liczbę słów między "wymiar" a liczbami
    // (np. "wymiary rozłożonego wózka:"), a opcjonalne cm/mm między liczbą
    // a "x" obsługuje zapis "58cm x 82cm" (jednostka wtrącona przed mnożnikiem).
    private static final java.util.regex.Pattern DIMENSIONS_PATTERN = java.util.regex.Pattern.compile(
            "wymiar\\w*[^\\d]{0,60}?(\\d+(?:[.,]\\d+)?)\\s*(?:cm|mm)?\\s*[x×]\\s*"
                    + "(\\d+(?:[.,]\\d+)?)\\s*(?:cm|mm)?(?:\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?))?",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    // Wzorzec 2 (fallback): opisy typu "Długość: 56 cm Szerokość: 36 cm Wysokość: 36 cm"
    // – trzy osobne etykietowane wartości zamiast "A x B x C". Kolejność w tekście
    // dowolna; mapowanie jest jednoznaczne przez samą etykietę, nie pozycję.
    private static final java.util.regex.Pattern LEN_PATTERN = java.util.regex.Pattern.compile(
            "d[łl]ugo[śs][ćc]\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*cm", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern WID_PATTERN = java.util.regex.Pattern.compile(
            "szeroko[śs][ćc]\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*cm", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern HEI_PATTERN = java.util.regex.Pattern.compile(
            "wysoko[śs][ćc]\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*cm", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /**
     * Zwraca [szerokość, długość, wysokość] w cm; 0.0 gdy nie znaleziono.
     * Produkty bez wymiarów w opisie (np. ubranka rozmiarowane S–XXL) legalnie
     * zwracają same zera – Carinio po prostu nie podaje dla nich centymetrów.
     */
    private double[] parseDimensions(String description) {
        double[] dims = new double[]{0, 0, 0};
        if (description == null || description.isBlank()) return dims;

        java.util.regex.Matcher m = DIMENSIONS_PATTERN.matcher(description);
        if (m.find()) {
            dims[0] = parseNum(m.group(1));
            dims[1] = parseNum(m.group(2));
            if (m.group(3) != null) dims[2] = parseNum(m.group(3));
            return dims;
        }

        // Fallback: format etykietowany. Wymagamy przynajmniej długości i szerokości,
        // żeby nie łapać przypadkowej pojedynczej wzmianki o "wysokości" w opisie.
        java.util.regex.Matcher lenM = LEN_PATTERN.matcher(description);
        java.util.regex.Matcher widM = WID_PATTERN.matcher(description);
        if (lenM.find() && widM.find()) {
            dims[1] = parseNum(lenM.group(1));
            dims[0] = parseNum(widM.group(1));
            java.util.regex.Matcher heiM = HEI_PATTERN.matcher(description);
            if (heiM.find()) dims[2] = parseNum(heiM.group(1));
        }
        return dims;
    }

    private double parseNum(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Odczyt atrybutu z elementu (pusty string jeśli brak). */
    private String attr(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name).trim() : "";
    }

    /** Odczyt treści pierwszego pasującego tagu dziecka (pusty string jeśli brak). */
    private String tag(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}
