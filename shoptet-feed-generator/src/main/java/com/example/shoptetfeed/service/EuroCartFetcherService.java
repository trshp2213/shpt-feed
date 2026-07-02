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
        // Disable external entity processing (security)
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
                products.add(parseProduct(o));
            } catch (Exception e) {
                log.warn("Skipping product id={} due to parse error: {}", o.getAttribute("id"), e.getMessage());
            }
        }

        log.info("Parsed {} products from feed", products.size());
        return products;
    }

    private EuroCartProduct parseProduct(Element o) {
        String id = o.getAttribute("id");
        String weightStr = o.getAttribute("weight");
        String stockStr = o.getAttribute("stock");

        double weight = weightStr.isBlank() ? 0.0 : Double.parseDouble(weightStr);
        int stock = stockStr.isBlank() ? 0 : Integer.parseInt(stockStr);

        String code = getTextContent(o, "Kod_towaru");
        if (code == null || code.isBlank()) {
            code = id; // fallback to euro-cart id
        }

        String priceStr = getTextContent(o, "price");
        double price = priceStr != null ? Double.parseDouble(priceStr) : 0.0;

        // Parse images
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
                String url = ((Element) iNodes.item(j)).getAttribute("url");
                if (url != null && !url.isBlank()) {
                    additionalImages.add(url);
                }
            }
        }

        // Parse EAN from attrs
        String ean = null;
        NodeList attrNodes = o.getElementsByTagName("a");
        for (int j = 0; j < attrNodes.getLength(); j++) {
            Element a = (Element) attrNodes.item(j);
            if ("EAN".equals(a.getAttribute("name"))) {
                ean = a.getTextContent().trim();
                break;
            }
        }

        return EuroCartProduct.builder()
                .id(id)
                .code(code)
                .name(nullToEmpty(getTextContent(o, "name")))
                .description(nullToEmpty(getTextContent(o, "desc")))
                .category(nullToEmpty(getTextContent(o, "cat")))
                .brand(nullToEmpty(getTextContent(o, "brand")))
                .price(price)
                .currency(nullToEmpty(getTextContent(o, "currency")))
                .stock(stock)
                .weight(weight)
                .mainImage(mainImage)
                .additionalImages(additionalImages)
                .ean(ean)
                .build();
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
