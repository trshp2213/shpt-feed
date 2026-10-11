package com.example.tuptifeed.runner;

import com.example.shoptetfeed.model.ShoptetItem;
import com.example.shoptetfeed.model.TranslationStore;
import com.example.shoptetfeed.service.ShoptetXmlWriterService;
import com.example.shoptetfeed.service.TranslationCacheService;
import com.example.shoptetfeed.service.translation.MultiLangTranslationService;
import com.example.tuptifeed.model.TuptiProduct;
import com.example.tuptifeed.service.TuptiConverterService;
import com.example.tuptifeed.service.TuptiFetcherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TuptiFeedRunner implements CommandLineRunner {

    private final TuptiFetcherService fetcherService;
    private final TuptiConverterService converterService;
    private final MultiLangTranslationService translationService;
    private final TranslationCacheService cacheService;
    private final ShoptetXmlWriterService writerService;

    /** Zabezpieczenie: podejrzanie mały feed dostawcy nie nadpisuje dobrego pliku. */
    @Value("${tupti.min-products:100}")
    private int minProducts;

    /** Maksymalny odsetek tekstów bez tłumaczenia SK, przy którym feed jeszcze zapisujemy. */
    @Value("${tupti.max-untranslated-percent:5}")
    private double maxUntranslatedPercent;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Tupti feed generator starting ===");

        List<TuptiProduct> products = fetcherService.fetch();
        if (products.size() < minProducts) {
            log.error("Pobrano tylko {} pozycji (min {}) - przerywam, żeby nie nadpisać istniejącego feedu",
                    products.size(), minProducts);
            return;
        }

        // Tłumaczenie PL -> SK (Azure). Cache zapisujemy od razu: pierwszy run tłumaczy
        // setki tysięcy znaków i nie chcemy tego stracić, gdyby kolejny krok się wywrócił.
        TranslationStore store = cacheService.load();
        translationService.translateAll(converterService.textsToTranslate(products), store);
        cacheService.save(store);

        // Bezpiecznik: nie publikujemy feedu z masą nieprzetłumaczonych (polskich) tekstów,
        // np. gdy Azure odmówił albo skończył się budżet. Poprzedni plik zostaje, run poprawi się sam.
        List<String> texts = converterService.textsToTranslate(products);
        Map<String, String> sk = store.lang("sk");
        long missing = texts.stream().filter(t -> !sk.containsKey(t)).count();
        if (!texts.isEmpty() && missing * 100.0 / texts.size() > maxUntranslatedPercent) {
            log.error("Brak tłumaczenia SK dla {} z {} tekstów (> {}%) - feed NIE został zapisany, "
                    + "kolejny run dokończy tłumaczenie", missing, texts.size(), maxUntranslatedPercent);
            return;
        }

        List<ShoptetItem> items = converterService.convert(products, store.lang("sk"));
        if (items.size() < minProducts) {
            log.error("Po konwersji zostało tylko {} pozycji (min {}) - przerywam bez zapisu feedu",
                    items.size(), minProducts);
            return;
        }
        writerService.write(items);

        log.info("=== Tupti feed complete: {} pozycji w feedzie Shoptet ===", items.size());
    }
}
