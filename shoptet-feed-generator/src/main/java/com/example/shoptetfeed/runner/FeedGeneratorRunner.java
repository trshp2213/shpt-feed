package com.example.shoptetfeed.runner;

import com.example.shoptetfeed.model.EuroCartProduct;
import com.example.shoptetfeed.model.ShoptetItem;
import com.example.shoptetfeed.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final DeepLTranslationService translationService;
    private final TranslationCacheService cacheService;
    private final NbpCurrencyService nbpService;
    private final FeedConverterService converterService;
    private final ShoptetXmlWriterService writerService;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Shoptet Feed Generator starting ===");

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

        // 3. Load existing translation cache and fill in any missing translations
        Map<String, String> cache = cacheService.load();
        translationService.translateAll(textsToTranslate, cache);

        // 4. Get current PLN→EUR rate
        double eurRate = nbpService.getPlnToEurRate();

        // 5. Convert to Shoptet format
        List<ShoptetItem> shoptetItems = converterService.convert(products, cache, eurRate);

        // 6. Write Shoptet XML
        writerService.write(shoptetItems);

        // 7. Persist updated translation cache
        cacheService.save(cache);

        log.info("=== Feed generation complete. {} products written. ===", shoptetItems.size());
    }
}
