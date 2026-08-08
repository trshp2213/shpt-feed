package com.example.shoptetfeed.service.translation;

import java.util.List;
import java.util.Map;

/**
 * Wspólny interfejs dla dostawców tłumaczeń (DeepL, Azure Translator).
 * Router (MultiLangTranslationService) sam dba o batching, cache i budżety
 * znaków – implementacje robią wyłącznie jedno wywołanie HTTP per batch.
 */
public interface TranslationProvider {

    /** Nazwa providera – klucz w konfiguracji translation.languages i w licznikach zużycia. */
    String name();

    /**
     * Tłumaczy podane teksty z sourceLang na targetLang (kody ISO, np. "pl" → "cs").
     * Zwraca mapę tekst źródłowy → tłumaczenie.
     */
    Map<String, String> translateBatch(List<String> texts, String sourceLang, String targetLang) throws Exception;

    /** Maksymalna liczba tekstów w jednym wywołaniu API. */
    int maxBatchTexts();

    /** Maksymalna łączna liczba znaków w jednym wywołaniu API. */
    int maxBatchChars();
}
