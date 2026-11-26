package com.example.supplierfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Jedna pozycja z feedu dostawcy, już po sparsowaniu (niezależna od formatu źródła). */
@Data
@Builder
public class SupplierProduct {
    /** Kod źródłowy (product_code / id) – bez prefiksu i normalizacji. */
    private String code;
    /** Wewnętrzne id dostawcy (EXTERNAL_CODE). */
    private String externalCode;
    private String url;
    private String name;
    /** Opis jako tekst lub HTML (Kotto składa HTML z atrybutów). */
    private String description;
    /** Ścieżka kategorii "A > B > C" (poziomy po polsku). */
    private String category;
    private String brand;
    private String ean;
    /** Cena brutto w walucie źródłowej. */
    private double price;
    private double weight;
    /** Wymiary w cm wyciągnięte z opisu (0 = brak). */
    private double widthCm;
    private double lengthCm;
    private double heightCm;
    private String mainImage;
    private List<String> additionalImages;
    private boolean available;
}
