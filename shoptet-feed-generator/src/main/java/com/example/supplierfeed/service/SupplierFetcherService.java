package com.example.supplierfeed.service;

import com.example.supplierfeed.model.SupplierProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pobiera i parsuje feed dostawcy. Dwa formaty (supplier.format):
 *  - offers    : Euro-Cart / Ceneo  (<offers><o id url price ...>) – Colibro i Kunert
 *  - gmerchant : Google Merchant RSS (<item> z przestrzenią g:)        – Kotto
 */
@Slf4j
@Service
public class SupplierFetcherService {

    @Value("${supplier.feed-url}")
    private String feedUrl;

    @Value("${supplier.format:offers}")
    private String format;

    @Value("${supplier.name:supplier}")
    private String name;

    @Value("${supplier.connect-timeout-seconds:30}")
    private int connectTimeoutSeconds;

    @Value("${supplier.fetch-attempts:3}")
    private int fetchAttempts;

    private HttpClient httpClient;

    public List<SupplierProduct> fetch() throws Exception {
        // adres bywa tajny (hash) – do logów nie trafia
        log.info("[{}] Pobieranie feedu (format {})", name, format);
        String xml = download();
        log.info("[{}] Pobrano {} znaków", name, xml.length());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        List<SupplierProduct> products = "gmerchant".equalsIgnoreCase(format) ? parseMerchant(doc) : parseOffers(doc);
        log.info("[{}] Sparsowano {} pozycji", name, products.size());
        return products;
    }

    private String download() throws Exception {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        Exception last = null;
        for (int attempt = 1; attempt <= fetchAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(feedUrl))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "Mozilla/5.0 (compatible; ShoptetFeedBot/1.0)")
                        .GET().build();
                HttpResponse<String> r = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() == 200) return r.body();
                last = new RuntimeException("HTTP " + r.statusCode());
            } catch (Exception e) {
                last = e;
            }
            log.warn("[{}] Pobranie nieudane (próba {}/{}): {}", name, attempt, fetchAttempts, last.getMessage());
            if (attempt < fetchAttempts) Thread.sleep(5000L * attempt);
        }
        throw new RuntimeException("Nie udało się pobrać feedu " + name + ": " + (last == null ? "?" : last.getMessage()));
    }

    // ------------------------------------------------------------------ offers (Colibro, Kunert)

    private List<SupplierProduct> parseOffers(Document doc) {
        List<SupplierProduct> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("o");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element o = (Element) nodes.item(i);
            try {
                String id = o.getAttribute("id").trim();
                String code = childText(o, "product_code");
                if (code.isBlank()) code = id;

                // dostępność: tag <availability> (Colibro) albo atrybuty avail/stock (Kunert;
                // brak stock = bez limitu)
                boolean available;
                String av = childText(o, "availability");
                if (!av.isBlank()) {
                    available = availabilityFromText(av);
                } else {
                    boolean flag = "1".equals(o.getAttribute("avail").trim());
                    String stock = o.getAttribute("stock").trim();
                    available = flag && (stock.isBlank() || parseDouble(stock) > 0);
                }

                String mainImage = null;
                List<String> additional = new ArrayList<>();
                Element imgs = child(o, "imgs");
                if (imgs != null) {
                    for (Element e : children(imgs)) {
                        String url = e.getAttribute("url").trim();
                        if (url.isBlank()) continue;
                        if ("main".equals(e.getLocalName())) mainImage = url;
                        else if ("i".equals(e.getLocalName())) additional.add(url);
                    }
                }
                if (mainImage == null && !additional.isEmpty()) mainImage = additional.remove(0);

                String ean = null, producer = null;
                Element attrs = child(o, "attrs");
                if (attrs != null) {
                    for (Element a : children(attrs)) {
                        String an = a.getAttribute("name").trim();
                        if ("EAN".equalsIgnoreCase(an)) ean = a.getTextContent().trim();
                        else if ("Producent".equalsIgnoreCase(an)) producer = a.getTextContent().trim();
                    }
                }
                String brand = childText(o, "brand");
                if (brand.isBlank() && producer != null) brand = producer;

                String descText = childText(o, "desc");
                double[] dims = parseDimensions(descText);
                result.add(SupplierProduct.builder()
                        .code(code)
                        .externalCode(id)
                        .url(o.getAttribute("url").trim())
                        .name(childText(o, "name"))
                        .description(descText)
                        .widthCm(dims[0]).lengthCm(dims[1]).heightCm(dims[2])
                        .category(categoryPath(childText(o, "cat")))
                        .brand(brand)
                        .ean(ean == null || ean.isBlank() ? null : ean)
                        .price(parseDouble(o.getAttribute("price")))
                        .weight(parseDouble(o.getAttribute("weight")))
                        .mainImage(mainImage)
                        .additionalImages(additional)
                        .available(available)
                        .build());
            } catch (Exception e) {
                log.warn("[{}] Pominięto <o id={}>: {}", name, ((Element) nodes.item(i)).getAttribute("id"), e.getMessage());
            }
        }
        return result;
    }

    private boolean availabilityFromText(String text) {
        String t = text.toLowerCase().trim();
        if (t.startsWith("niedost") || t.equals("brak") || t.equals("wyprzedany")) return false;
        if (t.startsWith("dost") || t.equals("w magazynie") || t.equals("na stanie")) return true;
        log.warn("[{}] Nieznana dostępność '{}' - traktuję jako niedostępny", name, text);
        return false;
    }

    // ------------------------------------------------------------------ Google Merchant (Kotto)

    private static final Pattern PRICE = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)");
    private static final Pattern DIGITS_EAN = Pattern.compile("\\d{8,14}");

    private List<SupplierProduct> parseMerchant(Document doc) {
        List<SupplierProduct> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            try {
                String id = childText(item, "id");
                Matcher pm = PRICE.matcher(childText(item, "price"));
                double price = pm.find() ? parseDouble(pm.group(1)) : 0;

                String qty = childText(item, "quantity");
                boolean available = childText(item, "availability").equalsIgnoreCase("in stock")
                        && (qty.isBlank() || parseDouble(qty) > 0);

                String main = childText(item, "image_link");
                List<String> additional = new ArrayList<>();
                for (Element e : children(item)) {
                    if ("additional_image_link".equals(e.getLocalName())) {
                        String u = e.getTextContent().trim();
                        if (!u.isBlank()) additional.add(u);
                    }
                }

                // opis: krótki opis + lista atrybutów technicznych jako HTML
                StringBuilder html = new StringBuilder();
                String desc = childText(item, "description");
                if (!desc.isBlank()) html.append("<p>").append(esc(desc)).append("</p>");
                StringBuilder li = new StringBuilder();
                for (Element d : children(item)) {
                    if (!"product_detail".equals(d.getLocalName())) continue;
                    String an = childText(d, "attribute_name");
                    String av = childText(d, "attribute_value");
                    if (an.isBlank() && av.isBlank()) continue;
                    if (an.endsWith(":")) li.append("<li><strong>").append(esc(an)).append("</strong> ").append(esc(av)).append("</li>");
                    else if (av.isBlank() || an.contains(av)) li.append("<li>").append(esc(an)).append("</li>");
                    else li.append("<li>").append(esc(an)).append(": ").append(esc(av)).append("</li>");
                }
                if (li.length() > 0) html.append("<ul>").append(li).append("</ul>");

                String size = childText(item, "size");
                String title = childText(item, "title");

                double[] dims = parseDimensions(html.toString().replaceAll("<[^>]+>", " "));
                result.add(SupplierProduct.builder()
                        .code(id)
                        .externalCode(childText(item, "item_group_id"))
                        .url(childText(item, "link"))
                        .name(title)
                        .description(html.toString())
                        .widthCm(dims[0]).lengthCm(dims[1]).heightCm(dims[2])
                        .category(categoryPath(childText(item, "product_type")))
                        .brand("")   // g:brand w tym feedzie to kolor - nieużywane
                        .ean(DIGITS_EAN.matcher(id).matches() ? id : null)
                        .price(price)
                        .weight(0)
                        .mainImage(main.isBlank() ? null : main)
                        .additionalImages(additional)
                        .available(available)
                        .build());
                if (!size.isBlank()) log.debug("[{}] {} rozmiar {}", name, id, size);
            } catch (Exception e) {
                log.warn("[{}] Pominięto <item>: {}", name, e.getMessage());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ pomocnicze

    // Wymiary: te same wzorce co w Carinio (EuroCartFetcherService) - "wymiary 65 x 100 cm"
    // albo osobne "Długość: 56 cm / Szerokość: 36 cm / Wysokość: 36 cm". Brak = zera.
    private static final Pattern DIMENSIONS_PATTERN = Pattern.compile(
            "wymiar\\w*[^\\d]{0,60}?(\\d+(?:[.,]\\d+)?)\\s*(?:cm|mm)?\\s*[x×]\\s*"
                    + "(\\d+(?:[.,]\\d+)?)\\s*(?:cm|mm)?(?:\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LEN = Pattern.compile("d[łl]ugo[śs][ćc]\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*cm", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WID = Pattern.compile("szeroko[śs][ćc]\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*cm", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HEI = Pattern.compile("wysoko[śs][ćc]\\s*:?\\s*(\\d+(?:[.,]\\d+)?)\\s*cm", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** [szerokość, długość, wysokość] w cm; zera gdy nie znaleziono. */
    static double[] parseDimensions(String text) {
        double[] d = new double[]{0, 0, 0};
        if (text == null || text.isBlank()) return d;
        Matcher m = DIMENSIONS_PATTERN.matcher(text);
        if (m.find()) {
            d[0] = parseDouble(m.group(1));
            d[1] = parseDouble(m.group(2));
            if (m.group(3) != null) d[2] = parseDouble(m.group(3));
            if (m.group(0).toLowerCase().contains("mm")) {   // wymiary w milimetrach -> cm
                for (int i = 0; i < 3; i++) d[i] = d[i] / 10;
            }
            return d;
        }
        Matcher l = LEN.matcher(text), w = WID.matcher(text);
        if (l.find() && w.find()) {
            d[1] = parseDouble(l.group(1));
            d[0] = parseDouble(w.group(1));
            Matcher h = HEI.matcher(text);
            if (h.find()) d[2] = parseDouble(h.group(1));
        }
        return d;
    }

    /** " / DZIECKO / Wózki dziecięce" lub "A > B" -> "DZIECKO > Wózki dziecięce". */
    static String categoryPath(String raw) {
        if (raw == null) return "";
        List<String> parts = new ArrayList<>();
        for (String s : raw.split("[/>]")) {
            if (!s.isBlank()) parts.add(s.trim());
        }
        return String.join(" > ", parts);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0;
        return Double.parseDouble(s.trim().replace(',', '.'));
    }

    private static Element child(Element parent, String local) {
        for (Element e : children(parent)) {
            if (local.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static String childText(Element parent, String local) {
        Element e = child(parent, local);
        return e == null ? "" : e.getTextContent().trim();
    }

    private static List<Element> children(Element parent) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) list.add((Element) n);
        }
        return list;
    }
}
