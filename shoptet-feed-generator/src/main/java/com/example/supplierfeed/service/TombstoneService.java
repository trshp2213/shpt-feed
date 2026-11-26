package com.example.supplierfeed.service;

import com.example.shoptetfeed.model.ShoptetItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.util.*;

/**
 * Produkty, które znikają z feedu dostawcy (Kunert i Kotto podają tylko to, co jest na stanie),
 * muszą w Shoptecie dostać stan 0. Pamiętamy więc kody ostatnio widzianych pozycji
 * (output/<dostawca>-seen.json) i gdy kod zniknie z feedu, wysyłamy "kikut":
 * ta sama nazwa/kategoria/producent/cena + Nedostupné (stan 0).
 * Kikut NIE ma opisu ani zdjęć (tagów brak = Shoptet ich nie rusza).
 * Po supplier.tombstone-days dniach nieobecności pozycja przestaje być wysyłana.
 */
@Slf4j
@Service
public class TombstoneService {

    @Value("${supplier.name:supplier}")
    private String name;

    @Value("${supplier.seen-path}")
    private String seenPath;

    @Value("${supplier.tombstone-days:60}")
    private int tombstoneDays;

    @Data
    public static class Seen {
        private String name;
        private String category;
        private String manufacturer;
        private String externalCode;
        private double price;
        private String currency;
        private String lastSeen;   // ISO yyyy-MM-dd
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Seen> state = new LinkedHashMap<>();

    /** Zwraca listę z feedu + kikuty pozycji, które zniknęły. Stan zapisuje dopiero save(). */
    public List<ShoptetItem> apply(List<ShoptetItem> current) {
        state = load();
        LocalDate today = LocalDate.now();
        Set<String> present = new HashSet<>();

        for (ShoptetItem it : current) {
            present.add(it.getCode());
            Seen s = new Seen();
            s.setName(it.getName());
            s.setCategory(it.getCategory());
            s.setManufacturer(it.getManufacturer());
            s.setExternalCode(it.getExternalCode());
            s.setPrice(it.getPrice());
            s.setCurrency(it.getCurrency());
            s.setLastSeen(today.toString());
            state.put(it.getCode(), s);
        }

        List<ShoptetItem> result = new ArrayList<>(current);
        int stubs = 0, expired = 0;
        for (Iterator<Map.Entry<String, Seen>> iter = state.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<String, Seen> e = iter.next();
            if (present.contains(e.getKey())) continue;
            Seen s = e.getValue();
            if (LocalDate.parse(s.getLastSeen()).plusDays(tombstoneDays).isBefore(today)) {
                iter.remove();
                expired++;
                continue;
            }
            result.add(ShoptetItem.builder()
                    .code(e.getKey())
                    .externalCode(s.getExternalCode())
                    .name(s.getName())
                    .manufacturer(s.getManufacturer())
                    .category(s.getCategory())
                    .price(s.getPrice())
                    .currency(s.getCurrency())
                    .availability("Nedostupné")
                    .visibility("visible")
                    .build());
            stubs++;
        }
        log.info("[{}] Kikuty (zniknęły z feedu -> stan 0): {}, wygasłe po {} dniach: {}",
                name, stubs, tombstoneDays, expired);
        return result;
    }

    public void save() {
        try {
            File f = new File(seenPath);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, state);
        } catch (Exception e) {
            log.error("[{}] Nie udało się zapisać {}: {}", name, seenPath, e.getMessage());
        }
    }

    private Map<String, Seen> load() {
        File f = new File(seenPath);
        if (!f.exists()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(f, new TypeReference<LinkedHashMap<String, Seen>>() {});
        } catch (Exception e) {
            log.warn("[{}] Nie udało się wczytać {}: {} - zaczynam od zera", name, seenPath, e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
