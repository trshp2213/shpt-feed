package com.example.shoptetfeed.service;

import com.example.shoptetfeed.model.TranslationStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Map;

/**
 * Persystuje cache tłumaczeń (v2, wielojęzyczny) + liczniki zużycia znaków
 * per provider per miesiąc w JSON commitowanym obok feed.xml.
 *
 * Automatyczna migracja: stary płaski format { "tekst PL": "tłumaczenie SK" }
 * jest wykrywany po braku pola "version" i przenoszony pod translations.sk.
 */
@Slf4j
@Service
public class TranslationCacheService {

    @Value("${output.cache-path}")
    private String cachePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public TranslationStore load() {
        File file = new File(cachePath);
        if (!file.exists()) {
            log.info("No translation cache found at {}, starting fresh", cachePath);
            return new TranslationStore();
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            if (root.has("version")) {
                TranslationStore store = objectMapper.treeToValue(root, TranslationStore.class);
                int langs = store.getTranslations().size();
                int entries = store.getTranslations().values().stream().mapToInt(Map::size).sum();
                log.info("Loaded translation cache v{}: {} languages, {} entries", store.getVersion(), langs, entries);
                return store;
            }
            // Stary format: płaska mapa PL→SK
            Map<String, String> flat = objectMapper.convertValue(root, new TypeReference<>() {});
            TranslationStore store = new TranslationStore();
            store.lang("sk").putAll(flat);
            log.info("Migrated legacy flat cache ({} SK entries) to v2 format", flat.size());
            return store;
        } catch (Exception e) {
            log.warn("Failed to read translation cache: {}. Starting fresh.", e.getMessage());
            return new TranslationStore();
        }
    }

    public void save(TranslationStore store) {
        try {
            File file = new File(cachePath);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, store);
            int entries = store.getTranslations().values().stream().mapToInt(Map::size).sum();
            log.info("Saved translation cache: {} languages, {} entries", store.getTranslations().size(), entries);
        } catch (Exception e) {
            log.error("Failed to save translation cache: {}", e.getMessage());
        }
    }
}
