package com.example.shoptetfeed.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists PL→SK translations to a JSON file committed alongside feed.xml.
 * This way each text is only sent to DeepL API once, saving the monthly free-tier quota.
 */
@Slf4j
@Service
public class TranslationCacheService {

    @Value("${output.cache-path}")
    private String cachePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, String> load() {
        File file = new File(cachePath);
        if (!file.exists()) {
            log.info("No translation cache found at {}, starting fresh", cachePath);
            return new HashMap<>();
        }
        try {
            Map<String, String> cache = objectMapper.readValue(file, new TypeReference<>() {});
            log.info("Loaded {} cached translations from {}", cache.size(), cachePath);
            return cache;
        } catch (Exception e) {
            log.warn("Failed to read translation cache: {}. Starting fresh.", e.getMessage());
            return new HashMap<>();
        }
    }

    public void save(Map<String, String> cache) {
        try {
            File file = new File(cachePath);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, cache);
            log.info("Saved {} translations to cache at {}", cache.size(), cachePath);
        } catch (Exception e) {
            log.error("Failed to save translation cache: {}", e.getMessage());
        }
    }
}
