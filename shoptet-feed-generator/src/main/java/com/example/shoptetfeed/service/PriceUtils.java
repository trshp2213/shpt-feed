package com.example.shoptetfeed.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Price rounding rules:
 *  - Last cent digit is 0 or 9 → keep as-is
 *  - Any other last digit → round UP to the nearest 9
 *
 * Examples:
 *   2.80 → 2.80   (already ends in 0)
 *   2.81 → 2.89   (round up to 9)
 *   2.84 → 2.89
 *   2.89 → 2.89   (already ends in 9)
 *   2.90 → 2.90   (already ends in 0)
 *   2.91 → 2.99
 *   2.99 → 2.99   (already ends in 9)
 */
public final class PriceUtils {

    private PriceUtils() {}

    public static double roundPrice(double price) {
        // Use BigDecimal to avoid floating-point precision issues
        BigDecimal bd = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
        int cents = bd.movePointRight(2).intValue();
        int lastDigit = cents % 10;

        if (lastDigit != 0 && lastDigit != 9) {
            cents = cents - lastDigit + 9;
        }

        return BigDecimal.valueOf(cents).movePointLeft(2).doubleValue();
    }

    /**
     * Converts PLN price to EUR using the given rate, then applies rounding.
     */
    public static double convertAndRound(double priceInPln, double plnPerEurRate) {
        double eur = priceInPln / plnPerEurRate;
        return roundPrice(eur);
    }
}
