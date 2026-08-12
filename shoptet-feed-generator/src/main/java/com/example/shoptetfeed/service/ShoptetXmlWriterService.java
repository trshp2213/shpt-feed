package com.example.shoptetfeed.service;

import com.example.shoptetfeed.model.ShoptetItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
public class ShoptetXmlWriterService {

    @Value("${output.feed-path}")
    private String feedPath;

    /** Stawka VAT sklepu (SK: 23%). Cena z feedu jest BRUTTO → PRICE_VAT = cena, PRICE = cena / (1 + vat). */
    @Value("${shoptet.vat-rate:23}")
    private double vatRate;

    public void write(List<ShoptetItem> items) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        doc.setXmlStandalone(true);

        Element shop = doc.createElement("SHOP");
        doc.appendChild(shop);

        for (ShoptetItem item : items) {
            Element shopItem = doc.createElement("SHOPITEM");
            shop.appendChild(shopItem);

            // ── Podstawowe informacje ──────────────────────────────────────
            addEl(doc, shopItem, "NAME", item.getName());
            addEl(doc, shopItem, "SHORT_DESCRIPTION", item.getShortDescription());
            addEl(doc, shopItem, "DESCRIPTION", item.getDescription());
            addEl(doc, shopItem, "MANUFACTURER", item.getManufacturer());
            addEl(doc, shopItem, "VISIBILITY", item.getVisibility());
            addEl(doc, shopItem, "ITEM_TYPE", "product");

            // ── Kategorie ─────────────────────────────────────────────────
            // Format Shoptet: "Carinio > Dečky"
            Element categories = doc.createElement("CATEGORIES");
            shopItem.appendChild(categories);
            addEl(doc, categories, "CATEGORY", item.getCategory());

            // ── Obrazy ────────────────────────────────────────────────────
            if (notBlank(item.getMainImage())) {
                Element images = doc.createElement("IMAGES");
                shopItem.appendChild(images);
                addImageEl(doc, images, item.getMainImage());
                if (item.getAdditionalImages() != null) {
                    for (String imgUrl : item.getAdditionalImages()) {
                        if (notBlank(imgUrl)) addImageEl(doc, images, imgUrl);
                    }
                }
            }

            // ── Kod / EAN ─────────────────────────────────────────────────
            addEl(doc, shopItem, "CODE", item.getCode());
            addEl(doc, shopItem, "EXTERNAL_CODE", item.getExternalCode());
            if (notBlank(item.getEan())) {
                addEl(doc, shopItem, "EAN", item.getEan());
            }

            // ── Cena ──────────────────────────────────────────────────────
            // PRICE i PRICE_VAT trzymają TĘ SAMĄ wartość brutto, tak jak reszta
            // katalogu (np. Bexa, gdzie PRICE od zawsze = cena sprzedaży wprost,
            // bez podziału netto/brutto). Wcześniej PRICE było netto - spójne
            // z frontem sklepu (który i tak priorytetowo pokazuje PRICE_VAT),
            // ale niespójne z synchronizacją "sklep -> BaseLinker" (Integracje ->
            // abckociky.sk -> Ceny), która kopiuje wprost wartość PRICE do grupy
            // cenowej Výchozí - przez co Carinio miało tam netto (12.35), a cała
            // reszta katalogu (Bexa i inni) ma tam brutto (39.90). Wyrównujemy.
            addEl(doc, shopItem, "CURRENCY", item.getCurrency());
            String priceStr = BigDecimal.valueOf(item.getPrice())
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
            addEl(doc, shopItem, "PRICE", priceStr);
            addEl(doc, shopItem, "PRICE_VAT", priceStr);
            addEl(doc, shopItem, "VAT", BigDecimal.valueOf(vatRate).stripTrailingZeros().toPlainString());

            // ── Dostępność + stan magazynowy ──────────────────────────────
            // Skladom    → VALUE=19 (dostępny u dostawcy)
            // Nedostupné → VALUE=0  (niedostępny)
            boolean inStock = "Skladom".equals(item.getAvailability());
            int stockValue = inStock ? 19 : 0;

            Element stock = doc.createElement("STOCK");
            shopItem.appendChild(stock);
            Element warehouses = doc.createElement("WAREHOUSES");
            stock.appendChild(warehouses);
            Element warehouse = doc.createElement("WAREHOUSE");
            warehouses.appendChild(warehouse);
            addEl(doc, warehouse, "NAME", "Sklad");
            addEl(doc, warehouse, "VALUE", String.valueOf(stockValue));

            if (notBlank(item.getAvailability())) {
                addEl(doc, shopItem, "AVAILABILITY", item.getAvailability());
            }
            addEl(doc, shopItem, "VISIBLE", "1");

            // ── Logistyka: waga + wymiary w JEDNYM bloku LOGISTIC ───────────
            // Poprzednia wersja pakowała wymiary w osobny wrapper <DIMENSIONS>,
            // którego oficjalna tabela tagów Shoptetu (Produkty → Import) nie
            // wymienia w ogóle – zna tylko płaskie "height"/"width"/"depth",
            // wymienione w tym samym rzędzie co "weight". W UI Shoptetu Hmotnosť/
            // Šírka/Dĺžka/Výška też siedzą razem w jednej sekcji "Detail produktu"
            // na zakładce Logistika. Stąd: WEIGHT/WIDTH/DEPTH/HEIGHT jako rodzeństwo
            // wewnątrz jednego <LOGISTIC>, nie osobny wrapper.
            boolean hasWeight = item.getWeight() > 0;
            boolean hasDims = item.getWidthCm() > 0 && item.getLengthCm() > 0;
            if (hasWeight || hasDims) {
                Element logistic = doc.createElement("LOGISTIC");
                shopItem.appendChild(logistic);
                if (hasWeight) {
                    String weightStr = BigDecimal.valueOf(item.getWeight())
                            .setScale(3, RoundingMode.HALF_UP)
                            .toPlainString();
                    addEl(doc, logistic, "WEIGHT", weightStr);
                }
                if (hasDims) {
                    // Mapowanie z feedu: szerokość→WIDTH, długość→DEPTH, wysokość→HEIGHT.
                    addEl(doc, logistic, "WIDTH", formatDim(item.getWidthCm()));
                    addEl(doc, logistic, "DEPTH", formatDim(item.getLengthCm()));
                    if (item.getHeightCm() > 0) {
                        addEl(doc, logistic, "HEIGHT", formatDim(item.getHeightCm()));
                    }
                }
            }
        }

        // ── Zapis do pliku ────────────────────────────────────────────────
        File outputFile = new File(feedPath);
        outputFile.getParentFile().mkdirs();

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        transformer.transform(new DOMSource(doc), new StreamResult(outputFile));
        log.info("Feed written to {} ({} products)", feedPath, items.size());
    }

    private String formatDim(double cm) {
        return BigDecimal.valueOf(cm).stripTrailingZeros().toPlainString();
    }

    private void addEl(Document doc, Element parent, String tag, String text) {
        if (text == null) return; // nie dodaj tagu jeśli wartość null
        Element el = doc.createElement(tag);
        el.setTextContent(text);
        parent.appendChild(el);
    }

    private void addImageEl(Document doc, Element parent, String url) {
        Element image = doc.createElement("IMAGE");
        image.setTextContent(url.trim());
        parent.appendChild(image);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
