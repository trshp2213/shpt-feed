package com.example.shoptetfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ShoptetItem {
    private String code;               // supplier code (Kod_towaru)
    private String externalCode;       // euro-cart internal id
    private String name;               // translated to SK
    private String description;        // translated to SK
    private String manufacturer;       // brand
    private String category;           // "Carinio > Dečky" etc.
    private String ean;
    private double price;              // in EUR, rounded
    private String currency;           // EUR
    private int stockCount;
    private String availability;       // "Skladom" / "Nedostupné"
    private double weight;             // in kg
    private String mainImage;
    private List<String> additionalImages;
    private String visibility;         // "visible"
}
