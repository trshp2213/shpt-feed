package com.example.supplierfeed;

import com.example.shoptetfeed.service.ShoptetXmlWriterService;
import com.example.shoptetfeed.service.TranslationCacheService;
import com.example.shoptetfeed.service.translation.AzureTranslatorProvider;
import com.example.shoptetfeed.service.translation.MultiLangTranslationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * Wspólna aplikacja dla dostawców: Colibro (Euro-Cart), Kunert i Kotto.
 * Każdy dostawca to OSOBNY pipeline (własny workflow, yml, feed, cache tłumaczeń),
 * a ten sam kod; dostawcę wybiera zmienna środowiskowa SUPPLIER (colibro | kunert | kotto),
 * która wskazuje plik konfiguracji <SUPPLIER>.yml.
 *
 * Uruchomienie:
 *   SUPPLIER=kunert mvn -q spring-boot:run -Dstart-class=com.example.supplierfeed.SupplierFeedApplication
 */
@SpringBootApplication(scanBasePackages = "com.example.supplierfeed")
@Import({
        MultiLangTranslationService.class,
        AzureTranslatorProvider.class,
        TranslationCacheService.class,
        ShoptetXmlWriterService.class
})
public class SupplierFeedApplication {

    public static void main(String[] args) {
        String supplier = System.getenv("SUPPLIER");
        if (supplier == null || supplier.isBlank()) supplier = System.getProperty("supplier");
        if (supplier == null || supplier.isBlank()) {
            System.err.println("Ustaw zmienną środowiskową SUPPLIER (colibro | kunert | kotto)");
            System.exit(2);
        }
        new SpringApplicationBuilder(SupplierFeedApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.name=" + supplier.trim().toLowerCase())
                .run(args);
    }
}
