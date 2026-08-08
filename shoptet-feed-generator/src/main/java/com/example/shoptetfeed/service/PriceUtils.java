package com.example.shoptetfeed.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Price rounding rules (identyczne dla groszy i pełnych jednostek):
 *  - Last digit is 0 or 9 → keep as-is
 *  - Any other last digit → round UP to the nearest 9
 *
 * Waluty groszowe (EUR, PLN, RON): reguła działa na ostatniej cyfrze groszy.
 *   2.80 → 2.80 | 2.81 → 2.89 | 2.91 → 2.99
 *
 * Waluty bezgroszowe (CZK, HUF): ceny na pełnych jednostkach,
 * reguła działa na ostatniej cyfrze kwoty.
 *   840 → 840 | 843 → 849 | 3985 → 3989
 */
public final class PriceUtils {

    /** Waluty, w których ceny podajemy w pełnych jednostkach (bez groszy). */
    public static final Set<String> WHOLE_UNIT_CURRENCIES = Set.of("CZK", "HUF");

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

    /** Zaokrąglenie 0/9 na pełnych jednostkach (CZK, HUF). */
    public static double roundPriceWholeUnits(double price) {
        long units = BigDecimal.valueOf(price).setScale(0, RoundingMode.HALF_UP).longValue();
        long lastDigit = units % 10;

        if (lastDigit != 0 && lastDigit != 9) {
            units = units - lastDigit + 9;
        }

        return (double) units;
    }

    /**
     * Converts PLN price to EUR using the given rate, then applies rounding.
     * (Zachowana dla zgodności z FeedConverterService / feedem Shoptet.)
     */
    public static double convertAndRound(double priceInPln, double plnPerEurRate) {
        double eur = priceInPln / plnPerEurRate;
        return roundPrice(eur);
    }

    /**
     * Konwersja PLN → waluta docelowa z regułą zaokrąglenia właściwą dla tej waluty.
     *
     * @param currency   kod waluty docelowej (PLN = brak konwersji, tylko zaokrąglenie)
     * @param priceInPln cena źródłowa w PLN
     * @param plnPerUnit ile PLN kosztuje 1 jednostka waluty (ignorowane dla PLN)
     */
    public static double convertAndRoundFor(String currency, double priceInPln, double plnPerUnit) {
        double value = "PLN".equalsIgnoreCase(currency) ? priceInPln : priceInPln / plnPerUnit;
        return WHOLE_UNIT_CURRENCIES.contains(currency.toUpperCase())
                ? roundPriceWholeUnits(value)
                : roundPrice(value);
    }
}
