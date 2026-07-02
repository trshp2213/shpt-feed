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

            // --- Basic product info ---
            addEl(doc, shopItem, "NAME", item.getName());
            addEl(doc, shopItem, "DESCRIPTION", item.getDescription());
            addEl(doc, shopItem, "MANUFACTURER", item.getManufacturer());
            addEl(doc, shopItem, "VISIBILITY", item.getVisibility());
            addEl(doc, shopItem, "ITEM_TYPE", "product");

            // --- Categories ---
            // Format: "Carinio > Dečky"  →  Shoptet uses " > " as separator
            Element categories = doc.createElement("CATEGORIES");
            shopItem.appendChild(categories);
            addEl(doc, categories, "CATEGORY", item.getCategory());

            // --- Images ---
            if (item.getMainImage() != null && !item.getMainImage().isBlank()) {
                Element images = doc.createElement("IMAGES");
                shopItem.appendChild(images);
                addImageEl(doc, images, item.getMainImage());
                if (item.getAdditionalImages() != null) {
                    for (String imgUrl : item.getAdditionalImages()) {
                        if (imgUrl != null && !imgUrl.isBlank()) {
                            addImageEl(doc, images, imgUrl);
                        }
                    }
                }
            }

            // --- Code / EAN ---
            addEl(doc, shopItem, "CODE", item.getCode());
            addEl(doc, shopItem, "EXTERNAL_CODE", item.getExternalCode());
            if (item.getEan() != null && !item.getEan().isBlank()) {
                addEl(doc, shopItem, "EAN", item.getEan());
            }

            // --- Price ---
            addEl(doc, shopItem, "CURRENCY", item.getCurrency());
            // Format price to exactly 2 decimal places, dot as separator
            String priceStr = BigDecimal.valueOf(item.getPrice())
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
            addEl(doc, shopItem, "PRICE", priceStr);

            // --- Stock ---
            Element stock = doc.createElement("STOCK");
            shopItem.appendChild(stock);
            Element warehouses = doc.createElement("WAREHOUSES");
            stock.appendChild(warehouses);
            Element warehouse = doc.createElement("WAREHOUSE");
            warehouses.appendChild(warehouse);
            addEl(doc, warehouse, "NAME", "Sklad");
            addEl(doc, warehouse, "VALUE", String.valueOf(item.getStockCount()));

            addEl(doc, shopItem, "AVAILABILITY", item.getAvailability());
            addEl(doc, shopItem, "VISIBLE", "1");

            // --- Logistics ---
            Element logistic = doc.createElement("LOGISTIC");
            shopItem.appendChild(logistic);
            // Weight in kg, 3 decimal places
            String weightStr = BigDecimal.valueOf(item.getWeight())
                    .setScale(3, RoundingMode.HALF_UP)
                    .toPlainString();
            addEl(doc, logistic, "WEIGHT", weightStr);
        }

        // --- Write to file ---
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

    private void addEl(Document doc, Element parent, String tag, String text) {
        Element el = doc.createElement(tag);
        el.setTextContent(text != null ? text : "");
        parent.appendChild(el);
    }

    private void addImageEl(Document doc, Element parent, String url) {
        Element image = doc.createElement("IMAGE");
        image.setTextContent(url.trim());
        parent.appendChild(image);
    }
}
