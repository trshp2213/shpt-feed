package com.example.shoptetfeed.service.baselinker;

import com.example.shoptetfeed.model.EuroCartProduct;
import com.example.shoptetfeed.model.TranslationStore;
import com.example.shoptetfeed.service.PriceUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Synchronizacja katalogu BaseLinkera z feedem Carinio.
 *
 * Zasady:
 *  - Idempotencja po SKU (= Kod_towaru): przed zapisem pobieramy istniejące
 *    produkty i matchujemy po polu sku – istniejące aktualizujemy
 *    (addInventoryProduct z product_id), nowe tworzymy. Zero duplikatów.
 *  - Ceny: jedna cena bazowa per waluta, wypełniamy WSZYSTKIE grupy cenowe
 *    o danej walucie tą samą kwotą (konwersja NBP + zaokrąglenie 0/9;
 *    CZK/HUF na pełnych jednostkach). Grupy w walutach spoza obsługiwanych
 *    (np. BGN) są pomijane z ostrzeżeniem w logu.
 *  - Teksty: text_fields z kluczami name|xx / description|xx dla każdego
 *    języka, dla którego istnieje tłumaczenie w cache. Język bez tłumaczenia
 *    (odroczony budżetem) jest po prostu pomijany – doleci w kolejnym runie.
 *    Pole domyślne (bez sufiksu) dostaje tekst w default_language katalogu,
 *    z fallbackiem na polski oryginał.
 *  - Produkty, które zniknęły z feedu, dostają stan 0 (nie są usuwane).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaselinkerSyncService {

    private final BaselinkerClient client;

    @Value("${baselinker.inventory-id:}")
    private String configuredInventoryId;

    @Value("${baselinker.warehouse-id:}")
    private String configuredWarehouseId;

    @Value("${baselinker.stock-when-available:19}")
    private int stockWhenAvailable;

    @Value("${baselinker.push-stock:true}")
    private boolean pushStock;

    @Value("${baselinker.vat-rate:0}")
    private double vatRate;

    @Value("${baselinker.tax-rate:23}")
    private double taxRate;

    @Value("#{${translation.languages}}")
    private Map<String, String> languageProviders;

    @Value("${translation.source-lang}")
    private String sourceLang;

    @Value("${currency.targets}")
    private List<String> targetCurrencies;

    public void sync(List<EuroCartProduct> products, TranslationStore store, Map<String, Double> rates) {
        if (!client.isConfigured()) {
            log.info("BASELINKER_TOKEN not set – skipping BaseLinker sync (Shoptet feed is unaffected)");
            return;
        }

        try {
            // 1. Katalog + język domyślny
            JsonNode inventory = resolveInventory();
            String inventoryId = inventory.get("inventory_id").asText();
            String defaultLang = inventory.path("default_language").asText(sourceLang);

            Set<String> availableLanguages = new HashSet<>();
            inventory.path("languages").forEach(n -> availableLanguages.add(n.asText()));
            log.info("BaseLinker inventory: id={} name='{}' default_language={} languages={}",
                    inventoryId, inventory.path("name").asText("?"), defaultLang, availableLanguages);

            // BaseLinker odrzuca text_fields dla języków spoza "Available languages"
            // skonfigurowanych w Products > Settings > Inventories > Edit. Ostrzegamy
            // raz na start runa zamiast wywalać się na pierwszym produkcie.
            if (!availableLanguages.contains(sourceLang)) {
                log.warn("Source language '{}' is not in BaseLinker inventory languages {} – "
                        + "explicit '{}' text fields will be skipped (unsuffixed default field is unaffected)",
                        sourceLang, availableLanguages, sourceLang);
            }
            for (String lang : languageProviders.keySet()) {
                if (!availableLanguages.contains(lang)) {
                    log.warn("Language '{}' is not in BaseLinker inventory languages {} – its translations "
                            + "will NOT be sent until you add it under Products > Settings > Inventories > "
                            + "Edit > Available languages", lang, availableLanguages);
                }
            }

            // 2. Grupy cenowe → waluta → lista group_id.
            //    Ten sam wzorzec co magazyny: getInventoryPriceGroups zwraca grupy
            //    CAŁEGO konta, a katalog akceptuje tylko te z inventory.price_groups[].
            Map<String, List<String>> groupsByCurrency = fetchPriceGroups(inventory);

            // 3. Magazyn – NIE osobnym wywołaniem API, tylko wprost z odpowiedzi
            //    getInventories (pole default_warehouse), która jest jedynym
            //    wiarygodnym źródłem "który magazyn należy do TEGO katalogu".
            String warehouseKey = resolveWarehouseKey(inventory);

            // 4. Istniejące produkty: SKU → product_id
            Map<String, String> existingBySku = fetchExistingProducts(inventoryId);

            // 5. Upsert produktów z feedu
            Set<String> feedSkus = new HashSet<>();
            int created = 0, updated = 0, skipped = 0;
            for (EuroCartProduct p : products) {
                if (p.getCode() == null || p.getCode().isBlank() || p.getPrice() <= 0) {
                    log.warn("Skipping product id={} – missing code or price <= 0", p.getId());
                    skipped++;
                    continue;
                }
                feedSkus.add(p.getCode());
                // Dopasowanie: najpierw po docelowym SKU (product_code). Jeśli nie
                // znaleziono – MOSTEK MIGRACYJNY: produkt mógł zostać wcześniej
                // utworzony z bugiem "Kod_towaru" (SKU = numeryczne id feedu, np.
                // "1259"). Jednorazowo dopasuj po id, żeby zaktualizować SKU na
                // właściwy zamiast tworzyć duplikat. Po jednym przebiegu wszystkie
                // SKU są już poprawne i ta gałąź przestaje się uruchamiać sama.
                String existingId = existingBySku.get(p.getCode());
                if (existingId == null) {
                    existingId = existingBySku.get(p.getId());
                    if (existingId != null) {
                        log.info("Migrating sku: product id={} had legacy numeric SKU '{}' – "
                                + "correcting to '{}'", p.getId(), p.getId(), p.getCode());
                    }
                }
                upsertProduct(inventoryId, existingId, p, store, rates, groupsByCurrency, warehouseKey,
                        defaultLang, availableLanguages);
                if (existingId != null) updated++; else created++;
            }

            // 6. Obsługa stanów po upsercie:
            //    - push-stock=true: stan 0 dla zarządzanych SKU zniknietych z feedu
            //    - push-stock=false: stany dostarcza sync sklepowy (shop_4014740);
            //      zerujemy WSZYSTKIE swoje wpisy w magazynie bl_, żeby suma
            //      magazynów nie dublowała stanu (19+19=38)
            int zeroed = pushStock
                    ? zeroStockForMissing(inventoryId, existingBySku, feedSkus, warehouseKey, store)
                    : neutralizeBlStock(inventoryId, existingBySku, feedSkus, warehouseKey, store);

            // 7. Aktualizacja listy zarządzanych SKU (persystowana z cache tłumaczeń)
            store.getManagedSkus().addAll(feedSkus);

            log.info("BaseLinker sync complete: {} created, {} updated, {} skipped, {} zeroed (gone from feed)",
                    created, updated, skipped, zeroed);
        } catch (Exception e) {
            log.error("BaseLinker sync failed: {} – Shoptet feed was generated normally, "
                    + "sync will retry on next run", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Katalog / grupy / magazyn
    // ------------------------------------------------------------------

    private JsonNode resolveInventory() throws Exception {
        JsonNode inventories = client.call("getInventories", Map.of()).get("inventories");
        if (inventories == null || inventories.isEmpty()) {
            throw new IllegalStateException("No inventories (catalogs) found in BaseLinker account");
        }
        if (configuredInventoryId != null && !configuredInventoryId.isBlank()) {
            for (JsonNode inv : inventories) {
                if (configuredInventoryId.equals(inv.get("inventory_id").asText())) {
                    return inv;
                }
            }
            throw new IllegalStateException("Configured inventory-id=" + configuredInventoryId
                    + " not found in BaseLinker account");
        }
        if (inventories.size() > 1) {
            StringBuilder options = new StringBuilder();
            for (JsonNode inv : inventories) {
                options.append("\n  - id=").append(inv.get("inventory_id").asText())
                        .append(" name='").append(inv.path("name").asText("?")).append("'");
            }
            throw new IllegalStateException("Multiple BaseLinker catalogs found – set BASELINKER_INVENTORY_ID "
                    + "to one of:" + options);
        }
        return inventories.get(0);
    }

    private Map<String, List<String>> fetchPriceGroups(JsonNode inventory) throws Exception {
        String inventoryId = inventory.get("inventory_id").asText();
        JsonNode groups = client.call("getInventoryPriceGroups", Map.of("inventory_id", inventoryId))
                .get("price_groups");

        // Grupy cenowe faktycznie podpięte do TEGO katalogu (inventory.price_groups[]).
        // getInventoryPriceGroups zwraca grupy całego konta; wysłanie ceny do grupy
        // spoza tej listy kończy się ERROR_INVALID_DATA ("Price group ... is not
        // included in the given inventory").
        Set<String> linkedToCatalog = new HashSet<>();
        inventory.path("price_groups").forEach(n -> linkedToCatalog.add(n.asText()));

        Set<String> supported = new HashSet<>();
        supported.add("PLN");
        for (String c : targetCurrencies) supported.add(c.toUpperCase());

        Map<String, List<String>> byCurrency = new LinkedHashMap<>();
        for (JsonNode g : groups) {
            String currency = g.path("currency").asText("").toUpperCase();
            String groupId = g.get("price_group_id").asText();
            String name = g.path("name").asText("?");
            if (!supported.contains(currency)) {
                log.warn("Price group '{}' (id={}) has unsupported currency {} – it will NOT be filled. "
                        + "Change its currency in BaseLinker if it should receive prices.", name, groupId, currency);
                continue;
            }
            if (!linkedToCatalog.contains(groupId)) {
                log.warn("Price group '{}' (id={}, {}) exists on the account but is NOT linked to catalog "
                        + "inventory_id={} – it will be skipped. Link it under Products > Settings > "
                        + "Inventories > Edit > Price groups to start receiving prices.",
                        name, groupId, currency, inventoryId);
                continue;
            }
            byCurrency.computeIfAbsent(currency, k -> new ArrayList<>()).add(groupId);
            log.info("Price group mapped: '{}' (id={}) → {}", name, groupId, currency);
        }

        for (String c : supported) {
            if (!byCurrency.containsKey(c)) {
                log.warn("No catalog-linked price group with currency {} – {} prices will not be sent "
                        + "until one is created and linked to the catalog", c, c);
            }
        }
        return byCurrency;
    }

    /**
     * Zwraca klucz magazynu, w którym MOŻNA ustawiać stany przez API, albo null.
     *
     * Przez API da się ustawiać stany wyłącznie w magazynach własnych BaseLinkera
     * (prefiks "bl_"). Magazyny "shop_*" / "warehouse_*" są synchronizowane
     * z zewnętrznych źródeł (sklep/hurtownia) i API je odrzuca – mylącym
     * komunikatem "Warehouse ... is not included in the given inventory".
     * Jeśli katalog ma tylko magazyn shop_* (jak tu: default_warehouse
     * synchronizowany ze Shoptetu), stany płyną już ścieżką Shoptet→BL
     * i nasz push byłby zbędnym duplikatem – wtedy zwracamy null i sync
     * po prostu pomija stany (ceny/teksty idą normalnie).
     */
    private String resolveWarehouseKey(JsonNode inventory) {
        if (configuredWarehouseId != null && !configuredWarehouseId.isBlank()) {
            return configuredWarehouseId;
        }
        List<String> all = new ArrayList<>();
        inventory.path("warehouses").forEach(n -> all.add(n.asText()));
        String defaultWarehouse = inventory.path("default_warehouse").asText("");

        // Preferuj default, jeśli jest edytowalny; potem pierwszy bl_ z listy
        if (defaultWarehouse.startsWith("bl_")) {
            log.info("Using BaseLinker default_warehouse: {} (API-editable)", defaultWarehouse);
            return defaultWarehouse;
        }
        for (String w : all) {
            if (w.startsWith("bl_")) {
                log.info("Default warehouse '{}' is store-synced (not API-editable) – "
                        + "using BL warehouse '{}' from catalog list {}", defaultWarehouse, w, all);
                return w;
            }
        }
        log.warn("Catalog has no API-editable (bl_) warehouse – warehouses={}, default='{}'. "
                + "Stock will NOT be sent via API (it is synced from the store instead). "
                + "To manage stock from this pipeline, link a BaseLinker warehouse to the catalog "
                + "or set BASELINKER_WAREHOUSE_ID.", all, defaultWarehouse);
        return null;
    }

    // ------------------------------------------------------------------
    // Produkty
    // ------------------------------------------------------------------

    private Map<String, String> fetchExistingProducts(String inventoryId) throws Exception {
        Map<String, String> bySku = new HashMap<>();
        int page = 1;
        while (true) {
            JsonNode products = client.call("getInventoryProductsList",
                    Map.of("inventory_id", inventoryId, "page", page)).get("products");
            if (products == null || products.isEmpty()) {
                break;
            }
            Iterator<Map.Entry<String, JsonNode>> it = products.fields();
            int count = 0;
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String sku = entry.getValue().path("sku").asText("");
                if (!sku.isBlank()) {
                    bySku.put(sku, entry.getKey());
                }
                count++;
            }
            if (count < 1000) {
                break; // ostatnia strona
            }
            page++;
        }
        log.info("Found {} existing products with SKU in BaseLinker catalog", bySku.size());
        return bySku;
    }

    private void upsertProduct(String inventoryId, String existingProductId, EuroCartProduct p,
                               TranslationStore store, Map<String, Double> rates,
                               Map<String, List<String>> groupsByCurrency, String warehouseKey,
                               String defaultLang, Set<String> availableLanguages) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("inventory_id", inventoryId);
        if (existingProductId != null) {
            params.put("product_id", existingProductId);
        }
        params.put("sku", p.getCode());
        params.put("tax", taxRate);
        if (p.getEan() != null && !p.getEan().isBlank()) {
            params.put("ean", p.getEan());
        }
        if (p.getWeight() > 0) {
            params.put("weight", p.getWeight());
        }
        // Wymiary sparsowane z opisu feedu ("wymiary: 65 x 100 cm") – natywne
        // pola BL widoczne na karcie produktu i formularzu wystawiania oferty.
        if (p.getWidthCm() > 0) params.put("width", p.getWidthCm());
        if (p.getLengthCm() > 0) params.put("length", p.getLengthCm());
        if (p.getHeightCm() > 0) params.put("height", p.getHeightCm());

        params.put("text_fields", buildTextFields(p, store, defaultLang, availableLanguages));
        params.put("prices", buildPrices(p, rates, groupsByCurrency));
        if (pushStock && warehouseKey != null) {
            params.put("stock", Map.of(warehouseKey, isAvailable(p.getAvailabilityText()) ? stockWhenAvailable : 0));
        }
        params.put("images", buildImages(p));

        client.call("addInventoryProduct", params);
    }

    private Map<String, String> buildTextFields(EuroCartProduct p, TranslationStore store, String defaultLang,
                                                Set<String> availableLanguages) {
        Map<String, String> fields = new LinkedHashMap<>();

        // Oryginał (polski) pod jawnym kluczem – tylko jeśli inventory ma ten język
        // dodany w Available languages (inaczej BaseLinker odrzuca cały request:
        // "Incorrect text field identifier: name|xx").
        if (availableLanguages.contains(sourceLang)) {
            putIfPresent(fields, "name|" + sourceLang, p.getName());
            putIfPresent(fields, "description|" + sourceLang, p.getDescription());
        }

        // Tłumaczenia – tylko języki dostępne w inventory ORAZ mające wpis w cache
        for (String lang : languageProviders.keySet()) {
            if (!availableLanguages.contains(lang)) continue;
            Map<String, String> cache = store.lang(lang);
            putIfPresent(fields, "name|" + lang, cache.get(p.getName()));
            putIfPresent(fields, "description|" + lang, cache.get(p.getDescription()));
        }

        // Pole domyślne katalogu: tekst w default_language, fallback na polski
        String defaultName = sourceLang.equals(defaultLang)
                ? p.getName()
                : store.lang(defaultLang).getOrDefault(p.getName(), p.getName());
        String defaultDesc = sourceLang.equals(defaultLang)
                ? p.getDescription()
                : store.lang(defaultLang).getOrDefault(p.getDescription(), p.getDescription());
        putIfPresent(fields, "name", defaultName);
        putIfPresent(fields, "description", defaultDesc);

        return fields;
    }

    private Map<String, Double> buildPrices(EuroCartProduct p, Map<String, Double> rates,
                                            Map<String, List<String>> groupsByCurrency) {
        // Feed Carinio podaje ceny NETTO, a grupy cenowe BaseLinkera są BRUTTO –
        // doliczamy VAT do bazy PLN, dopiero potem konwersja walut i zaokrąglenie 0/9.
        // (Feed Shoptet celowo bez zmian: tam element PRICE jest netto i Shoptet
        // sam dolicza VAT według własnych ustawień.)
        double grossPln = p.getPrice() * (1.0 + vatRate / 100.0);

        Map<String, Double> prices = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : groupsByCurrency.entrySet()) {
            String currency = entry.getKey();
            double rate = "PLN".equals(currency) ? 1.0 : rates.getOrDefault(currency, 0.0);
            if (!"PLN".equals(currency) && rate <= 0) {
                continue; // brak kursu – nie wysyłamy ceny w tej walucie
            }
            double price = PriceUtils.convertAndRoundFor(currency, grossPln, rate);
            for (String groupId : entry.getValue()) {
                prices.put(groupId, price);
            }
        }
        return prices;
    }

    private Map<String, String> buildImages(EuroCartProduct p) {
        Map<String, String> images = new LinkedHashMap<>();
        int idx = 0;
        if (p.getMainImage() != null && !p.getMainImage().isBlank()) {
            images.put(String.valueOf(idx++), "url:" + p.getMainImage());
        }
        if (p.getAdditionalImages() != null) {
            for (String img : p.getAdditionalImages()) {
                if (idx >= 16) break; // limit BaseLinkera: 16 zdjęć
                if (img != null && !img.isBlank()) {
                    images.put(String.valueOf(idx++), "url:" + img);
                }
            }
        }
        return images;
    }

    /**
     * Tryb push-stock=false: stany dostarcza synchronizacja sklepowa, więc wpisy
     * tego pipeline'u w magazynie bl_ tylko dublują stan w "sumie magazynów".
     * Zerujemy je dla wszystkich zarządzanych SKU (bieżących i historycznych),
     * które istnieją w katalogu. Idempotentne – kolejne runy to no-op po stronie
     * wartości. Cudzych produktów nie dotykamy.
     */
    private int neutralizeBlStock(String inventoryId, Map<String, String> existingBySku,
                                  Set<String> feedSkus, String warehouseKey,
                                  TranslationStore store) throws Exception {
        if (warehouseKey == null) {
            return 0;
        }
        Set<String> toNeutralize = new LinkedHashSet<>(store.getManagedSkus());
        toNeutralize.addAll(feedSkus);
        toNeutralize.retainAll(existingBySku.keySet());
        if (toNeutralize.isEmpty()) {
            return 0;
        }

        Map<String, Object> batch = new LinkedHashMap<>();
        for (String sku : toNeutralize) {
            batch.put(existingBySku.get(sku), Map.of(warehouseKey, 0));
            if (batch.size() == 1000) {
                client.call("updateInventoryProductsStock",
                        Map.of("inventory_id", inventoryId, "products", batch));
                batch = new LinkedHashMap<>();
            }
        }
        if (!batch.isEmpty()) {
            client.call("updateInventoryProductsStock",
                    Map.of("inventory_id", inventoryId, "products", batch));
        }
        log.info("push-stock=false: neutralized {} stock entries in warehouse {} "
                + "(stock is delivered by the store sync)", toNeutralize.size(), warehouseKey);
        return 0; // nic nie "zniknęło z feedu" – to tylko normalizacja
    }

    private int zeroStockForMissing(String inventoryId, Map<String, String> existingBySku,
                                    Set<String> feedSkus, String warehouseKey,
                                    TranslationStore store) throws Exception {
        if (warehouseKey == null) {
            log.info("No API-editable warehouse – skipping stock zeroing for products gone from feed "
                    + "(stock is managed by the store sync)");
            return 0;
        }
        // Kandydaci: tylko SKU, które pipeline sam wypchnął, nie ma ich w bieżącym
        // feedzie, a wciąż istnieją w katalogu BL. Nigdy produkty spoza feedu Carinio.
        Set<String> toZero = new LinkedHashSet<>(store.getManagedSkus());
        toZero.removeAll(feedSkus);
        toZero.retainAll(existingBySku.keySet());
        if (toZero.isEmpty()) {
            return 0;
        }

        // Limit API: max 1000 produktów per wywołanie updateInventoryProductsStock
        Map<String, Object> batch = new LinkedHashMap<>();
        for (String sku : toZero) {
            batch.put(existingBySku.get(sku), Map.of(warehouseKey, 0));
            log.info("Product sku={} gone from feed – stock zeroed", sku);
            if (batch.size() == 1000) {
                client.call("updateInventoryProductsStock",
                        Map.of("inventory_id", inventoryId, "products", batch));
                batch = new LinkedHashMap<>();
            }
        }
        if (!batch.isEmpty()) {
            client.call("updateInventoryProductsStock",
                    Map.of("inventory_id", inventoryId, "products", batch));
        }

        // Wyzerowane przestają być "zarządzane"; wrócą na listę, jeśli wrócą do feedu
        store.getManagedSkus().removeAll(toZero);
        return toZero.size();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Ta sama semantyka co FeedConverterService.mapAvailability – "Dostępny" itd. */
    private boolean isAvailable(String availabilityText) {
        if (availabilityText == null || availabilityText.isBlank()) return false;
        return switch (availabilityText.toLowerCase().trim()) {
            case "dostępny", "dostepny", "dostępne", "w magazynie", "na stanie" -> true;
            default -> false;
        };
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
