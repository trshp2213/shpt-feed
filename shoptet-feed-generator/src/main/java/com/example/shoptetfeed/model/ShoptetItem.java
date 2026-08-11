package com.example.shoptetfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ShoptetItem {
    private String code;               // Kod_towaru
    private String externalCode;       // euro-cart internal id
    private String productUrl;         // URL produktu od dostawcy
    private String name;               // przetłumaczone na SK
    private String description;        // przetłumaczone na SK
    private String shortDescription;   // 1. zdanie opisu SK (SHORT_DESCRIPTION)
    private String manufacturer;       // brand
    private String category;           // "Carinio > Dečky"
    private String ean;
    private double price;              // w EUR, zaokrąglona
    private String currency;           // EUR
    private String availability;       // "Skladom" / "Nedostupné"
    private double weight;             // w kg (0.0 = nieznana)
    private double widthCm;            // wymiary produktu z opisu (0.0 = brak)
    private double lengthCm;
    private double heightCm;
    private String mainImage;
    private List<String> additionalImages;
    private String visibility;         // "visible"
}
