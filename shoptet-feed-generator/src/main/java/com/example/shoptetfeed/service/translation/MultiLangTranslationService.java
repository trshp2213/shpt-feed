package com.example.shoptetfeed.service.translation;

import com.example.shoptetfeed.model.TranslationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Router tłumaczeń: dla każdego skonfigurowanego języka wybiera providera
 * (deepl/azure), tłumaczy tylko teksty brakujące w cache i pilnuje
 * miesięcznych budżetów znaków.
 *
 * Teksty, które nie mieszczą się w budżecie danego miesiąca, są po prostu
 * pomijane – doleci je pierwszy cron w kolejnym miesiącu (liczniki są
 * kluczowane yyyy-MM, więc reset następuje automatycznie).
 *
 * Awaria jednego providera (np. wyczerpany limit DeepL → HTTP 456) nie
 * przerywa całego runa – logujemy błąd i przechodzimy do kolejnego języka.
 */
@Slf4j
@Service
public class MultiLangTranslationService {

    /** język docelowy → nazwa providera, w kolejności priorytetu z application.yml */
    @Value("#{${translation.languages}}")
    private Map<String, String> languageProviders;

    /** provider → miesięczny limit znaków */
    @Value("#{${translation.monthly-limits}}")
    private Map<String, Long> monthlyLimits;

    @Value("${translation.source-lang}")
    private String sourceLang;

    private final Map<String, TranslationProvider> providers;

    public MultiLangTranslationService(List<TranslationProvider> providerList) {
        this.providers = new LinkedHashMap<>();
        for (TranslationProvider p : providerList) {
            this.providers.put(p.name(), p);
        }
    }

    public void translateAll(List<String> texts, TranslationStore store) {
        String month = YearMonth.now().toString(); // np. "2026-07"

        List<String> unique = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();

        for (Map.Entry<String, String> entry : languageProviders.entrySet()) {
            String lang = entry.getKey();
            String providerName = entry.getValue();
            TranslationProvider provider = providers.get(providerName);
            if (provider == null) {
                log.error("Unknown translation provider '{}' configured for language '{}' – skipping", providerName, lang);
                continue;
            }

            Map<String, String> cache = store.lang(lang);
            List<String> missing = unique.stream().filter(t -> !cache.containsKey(t)).toList();
            if (missing.isEmpty()) {
                log.info("[{}] all {} texts already cached", lang, unique.size());
                continue;
            }

            long limit = monthlyLimits.getOrDefault(providerName, Long.MAX_VALUE);
            int translated = 0;
            int deferred = 0;

            List<String> batch = new ArrayList<>();
            int batchChars = 0;

            try {
                for (String text : missing) {
                    long used = store.getUsage(month, providerName);
                    if (used + batchChars + text.length() > limit) {
                        deferred++;
                        continue; // budżet wyczerpany dla tego tekstu – doleci w nowym miesiącu
                    }
                    if (batch.size() >= provider.maxBatchTexts()
                            || batchChars + text.length() > provider.maxBatchChars()) {
                        translated += flush(provider, batch, batchChars, lang, cache, store, month);
                        batch = new ArrayList<>();
                        batchChars = 0;
                    }
                    batch.add(text);
                    batchChars += text.length();
                }
                if (!batch.isEmpty()) {
                    translated += flush(provider, batch, batchChars, lang, cache, store, month);
                }
            } catch (Exception e) {
                log.error("[{}] translation via {} failed: {} – continuing with next language "
                        + "(already translated texts stay in cache)", lang, providerName, e.getMessage());
            }

            log.info("[{}] {} translated via {}, {} deferred to next month (usage {}: {}/{} chars)",
                    lang, translated, providerName, deferred,
                    month, store.getUsage(month, providerName), limit);
        }
    }

    private int flush(TranslationProvider provider, List<String> batch, int batchChars,
                      String lang, Map<String, String> cache,
                      TranslationStore store, String month) throws Exception {
        Map<String, String> result = provider.translateBatch(batch, sourceLang, lang);
        cache.putAll(result);
        store.addUsage(month, provider.name(), batchChars);
        return result.size();
    }
}
