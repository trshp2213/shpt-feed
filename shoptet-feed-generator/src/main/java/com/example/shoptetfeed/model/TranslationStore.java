package com.example.shoptetfeed.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache tłumaczeń v2 – persystowany w output/translation-cache.json.
 *
 * Struktura pliku:
 * {
 *   "version": 2,
 *   "translations": {
 *     "sk": { "tekst PL": "tłumaczenie SK", ... },
 *     "cs": { ... },
 *     ...
 *   },
 *   "usage": {
 *     "2026-07": { "deepl": 12345, "azure": 987654 }
 *   }
 * }
 *
 * Stary format (płaska mapa PL→SK) jest migrowany automatycznie
 * przez TranslationCacheService – żadne istniejące tłumaczenie SK nie przepada.
 */
@Data
public class TranslationStore {

    private int version = 2;

    /** język docelowy → (tekst źródłowy → tłumaczenie) */
    private Map<String, Map<String, String>> translations = new LinkedHashMap<>();

    /** miesiąc (yyyy-MM) → (provider → zużyte znaki) */
    private Map<String, Map<String, Long>> usage = new LinkedHashMap<>();

    @JsonIgnore
    public Map<String, String> lang(String lang) {
        return translations.computeIfAbsent(lang, k -> new LinkedHashMap<>());
    }

    @JsonIgnore
    public long getUsage(String month, String provider) {
        return usage.getOrDefault(month, Map.of()).getOrDefault(provider, 0L);
    }

    @JsonIgnore
    public void addUsage(String month, String provider, long chars) {
        usage.computeIfAbsent(month, k -> new LinkedHashMap<>()).merge(provider, chars, Long::sum);
    }
}
