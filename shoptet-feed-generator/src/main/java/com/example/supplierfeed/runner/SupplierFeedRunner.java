package com.example.supplierfeed.runner;

import com.example.shoptetfeed.model.ShoptetItem;
import com.example.shoptetfeed.model.TranslationStore;
import com.example.shoptetfeed.service.ShoptetXmlWriterService;
import com.example.shoptetfeed.service.TranslationCacheService;
import com.example.shoptetfeed.service.translation.MultiLangTranslationService;
import com.example.supplierfeed.model.SupplierProduct;
import com.example.supplierfeed.service.SupplierConverterService;
import com.example.supplierfeed.service.SupplierFetcherService;
import com.example.supplierfeed.service.TombstoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** fetch -> guard -> tłumaczenie SK (cache) -> guard -> konwersja -> kikuty -> zapis feedu. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierFeedRunner implements CommandLineRunner {

    private final SupplierFetcherService fetcher;
    private final SupplierConverterService converter;
    private final MultiLangTranslationService translationService;
    private final TranslationCacheService cacheService;
    private final TombstoneService tombstones;
    private final ShoptetXmlWriterService writer;

    @Value("${supplier.name:supplier}")
    private String name;

    /** Zbyt mały feed (awaria dostawcy) nie nadpisuje dobrego pliku ani nie zeruje stanów. */
    @Value("${supplier.min-products:10}")
    private int minProducts;

    @Value("${supplier.max-untranslated-percent:5}")
    private double maxUntranslatedPercent;

    /** Gdy dostawca blokuje część adresów GitHuba: nieudane pobranie = run kończy się bez zmian (zielony), kolejny nadrobi. */
    @Value("${supplier.skip-on-fetch-failure:false}")
    private boolean skipOnFetchFailure;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== [{}] feed generator starting ===", name);

        List<SupplierProduct> products;
        try {
            products = fetcher.fetch();
        } catch (Exception e) {
            if (!skipOnFetchFailure) throw e;
            log.error("[{}] Pobranie feedu nieudane ({}) - zostaje poprzedni plik, kolejny run spróbuje ponownie", name, e.getMessage());
            return;
        }
        if (products.size() < minProducts) {
            log.error("[{}] Pobrano tylko {} pozycji (min {}) - przerywam bez zmian w feedzie", name, products.size(), minProducts);
            return;
        }

        TranslationStore store = cacheService.load();
        List<String> texts = converter.textsToTranslate(products);
        translationService.translateAll(texts, store);
        cacheService.save(store);

        Map<String, String> sk = store.lang("sk");
        long missing = texts.stream().filter(t -> !sk.containsKey(t)).count();
        if (!texts.isEmpty() && missing * 100.0 / texts.size() > maxUntranslatedPercent) {
            log.error("[{}] Brak tłumaczenia SK dla {} z {} tekstów (> {}%) - feed NIE zapisany, kolejny run dokończy",
                    name, missing, texts.size(), maxUntranslatedPercent);
            return;
        }

        List<ShoptetItem> items = converter.convert(products, sk);
        if (items.size() < minProducts) {
            log.error("[{}] Po konwersji tylko {} pozycji (min {}) - przerywam bez zapisu", name, items.size(), minProducts);
            return;
        }

        List<ShoptetItem> withStubs = tombstones.apply(items);
        writer.write(withStubs);
        tombstones.save();

        log.info("=== [{}] complete: {} pozycji z feedu + {} kikutów ===", name, items.size(), withStubs.size() - items.size());
    }
}
