package com.example.tuptifeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Jedna pozycja do feedu Shoptet. Produkt z wariantami jest rozbity na osobne
 * pozycje (po jednej na wariant) - produkt-rodzic nie trafia do feedu.
 */
@Data
@Builder
public class TuptiProduct {
    private String code;              // symbol produktu / wariantu (= CODE w Shoptet)
    private String externalCode;      // "id" albo "id-variantId"
    private String name;              // nazwa PL produktu
    private String variantLabel;      // np. "Biały", "Ilość modułów: 3"; null dla zwykłych
    private String shortDescription;  // HTML, PL
    private String description;       // HTML, PL
    private String safety;            // tekst, PL (już z <br> między zdaniami)
    private String category;          // ścieżka PL "A > B > C"
    private String producer;
    private String ean;
    private String priceNet;          // tak jak w feedzie dostawcy (w EUR)
    private int store;
    private boolean statusAvailable;
    private double weight;
    private double widthCm;           // wymiary opakowania
    private double lengthCm;
    private double heightCm;
    private String age;               // np. "3+"
    private List<String> images;
}
