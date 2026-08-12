package com.example.shoptetfeed.runner;

import com.example.shoptetfeed.model.EuroCartProduct;
import com.example.shoptetfeed.model.ShoptetItem;
import com.example.shoptetfeed.model.TranslationStore;
import com.example.shoptetfeed.service.*;
import com.example.shoptetfeed.service.baselinker.BaselinkerSyncService;
import com.example.shoptetfeed.service.translation.MultiLangTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedGeneratorRunner implements CommandLineRunner {

    private final EuroCartFetcherService fetcherService;
    private final MultiLangTranslationService translationService;
    private final TranslationCacheService cacheService;
    private final NbpCurrencyService nbpService;
    private final FeedConverterService converterService;
    private final ShoptetXmlWriterService writerService;
    private final BaselinkerSyncService baselinkerSyncService;

    @Value("${currency.targets}")
    private List<String> targetCurrencies;

    // Stały kurs PLN->EUR dla ceny w feedzie Shoptet (nie NBP). Cena zmienia
    // się tylko wtedy, gdy Carinio zmieni cenę w swoim feedzie - nie przy
    // codziennych wahaniach kursu. Zmieniaj ręcznie, gdy uznasz to za potrzebne.
    @Value("${shoptet.eur-rate}")
    private double shoptetEurRate;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Feed Generator starting (Shoptet + BaseLinker) ===");

        // 1. Fetch raw products from euro-cart
        List<EuroCartProduct> products = fetcherService.fetchProducts();
        if (products.isEmpty()) {
            log.warn("No products fetched – aborting to avoid overwriting existing feed with an empty file");
            return;
        }

        // 2. Collect all texts that need translation
        List<String> textsToTranslate = new ArrayList<>();
        for (EuroCartProduct p : products) {
            textsToTranslate.add(p.getName());
            textsToTranslate.add(p.getDescription());
            textsToTranslate.add(p.getCategory());
        }

        // 3. Load cache (v2, with automatic legacy migration) and translate
        //    all configured languages via their providers (DeepL / Azure)
        TranslationStore store = cacheService.load();
        translationService.translateAll(textsToTranslate, store);

        // 4. NBP rates – zachowane dla BaselinkerSyncService (na wypadek
        //    ponownego włączenia baselinker.push-prices w przyszłości).
        //    Cena w feedzie Shoptet NIE używa już tych kursów - patrz krok 5.
        Map<String, Double> rates = nbpService.getRates(targetCurrencies);

        // 5. Convert to Shoptet format – stały kurs shoptet.eur-rate (nie NBP),
        //    zaokrąglenie do pełnych 10 eurocentów (patrz PriceUtils).
        List<ShoptetItem> shoptetItems = converterService.convert(products, store.lang("sk"), shoptetEurRate);

        // 6. Write Shoptet XML
        writerService.write(shoptetItems);

        // 7. Persist updated translation cache (translations + char usage counters)
        cacheService.save(store);

        // 8. Push catalog to BaseLinker (no-op with a log message when token absent;
        //    a sync failure never breaks the Shoptet feed above)
        baselinkerSyncService.sync(products, store, rates);

        // 9. Drugi zapis: sync uzupełnia store.managedSkus (lista SKU zarządzanych
        //    przez pipeline w BL) – bez tego zapisu nie przetrwałaby między runami
        cacheService.save(store);

        log.info("=== Feed generation complete. {} products written to Shoptet feed. ===", shoptetItems.size());
    }
}
