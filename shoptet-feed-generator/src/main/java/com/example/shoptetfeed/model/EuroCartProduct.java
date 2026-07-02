package com.example.shoptetfeed.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EuroCartProduct {
    private String id;           // <o id="...">
    private String code;         // <Kod_towaru>
    private String name;         // <name>
    private String description;  // <desc>
    private String category;     // <cat>
    private String brand;        // <brand>
    private double price;        // <price> in PLN
    private String currency;     // <currency>
    private int stock;           // <o stock="...">
    private double weight;       // <o weight="..."> in kg
    private String mainImage;    // <imgs><main url="...">
    private List<String> additionalImages; // <imgs><i url="...">
    private String ean;          // <attrs><a name="EAN">
}
