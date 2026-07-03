package com.example.shoptetfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EuroCartProduct {
    private String id;                // attrybut <o id="...">
    private String url;               // attrybut <o url="...">
    private String code;              // tag <Kod_towaru>
    private String name;              // tag <name>
    private String description;       // tag <desc>
    private String category;          // tag <cat>
    private String brand;             // tag <brand>
    private double price;             // attrybut <o price="...">
    private String currency;          // attrybut <o currency="...">
    private String availabilityText;  // tag <availability> np. "Dostępny"
    private double weight;            // attrybut <o weight="...">
    private String mainImage;         // <imgs><main url="...">
    private List<String> additionalImages; // <imgs><i url="...">
    private String ean;               // <attrs><a name="EAN">
}
