package com.example.tuptifeed;

import com.example.shoptetfeed.service.ShoptetXmlWriterService;
import com.example.shoptetfeed.service.TranslationCacheService;
import com.example.shoptetfeed.service.translation.AzureTranslatorProvider;
import com.example.shoptetfeed.service.translation.MultiLangTranslationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * Osobna aplikacja dla feedu tupti.wood. Nie dzieli kontekstu Springa z Carinio:
 * skanuje tylko własny pakiet i jawnie importuje WYŁĄCZNIE klasy Carinio, które
 * wykorzystuje (router tłumaczeń + provider Azure, cache, writer XML Shoptet).
 * Konfiguracja pochodzi z tupti.yml (nie z application.yml).
 *
 * Uruchomienie:
 *   mvn -q spring-boot:run -Dstart-class=com.example.tuptifeed.TuptiFeedApplication
 * (bez flagi pom.xml uruchamia Carinio - ShoptetFeedApplication)
 */
@SpringBootApplication(scanBasePackages = "com.example.tuptifeed")
@Import({
        MultiLangTranslationService.class,
        AzureTranslatorProvider.class,
        TranslationCacheService.class,
        ShoptetXmlWriterService.class
})
public class TuptiFeedApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(TuptiFeedApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=tupti")
                .run(args);
    }
}
