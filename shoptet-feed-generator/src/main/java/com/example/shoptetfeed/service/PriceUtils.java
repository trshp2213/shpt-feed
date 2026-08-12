package com.example.shoptetfeed.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Price rounding rules:
 *
 * roundPrice / roundPriceWholeUnits / convertAndRoundFor – stara reguła 0/9,
 * zachowana dla ewentualnego ponownego włączenia wysyłki cen do BaseLinkera
 * (baselinker.push-prices), obecnie nieużywana w praktyce (push-prices: false).
 *
 * roundToNearestTenCents – AKTUALNA reguła dla ceny w feedzie Shoptet: cena
 * zaokrąglana do pełnych 10 eurocentów (,10 ,20 ,30 ...), najbliższa wartość
 * (HALF_UP przy remisie). Używana przez convertAndRound.
 */
public final class PriceUtils {

    /** Waluty, w których ceny podajemy w pełnych jednostkach (bez groszy). */
    public static final Set<String> WHOLE_UNIT_CURRENCIES = Set.of("CZK", "HUF");

    private PriceUtils() {}

    /** Stara reguła 0/9 – patrz komentarz klasy. Zachowana, nieużywana obecnie. */
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
     * Zaokrąglenie do pełnych 10 eurocentów (0,10 / 0,20 / 0,30 ...),
     * do najbliższej wartości (remis w górę). Reguła aktualnie używana
     * dla ceny w feedzie Shoptet.
     */
    public static double roundToNearestTenCents(double price) {
        BigDecimal bd = BigDecimal.valueOf(price)
                .divide(BigDecimal.valueOf(0.10), 10, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(0.10));
        return bd.setScale(2, RoundingMode.HALF_UP).doubleValue();
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
     * Converts PLN price to EUR using the given (obecnie stałego, patrz
     * shoptet.eur-rate) rate, then rounds to nearest 10 eurocents.
     */
    public static double convertAndRound(double priceInPln, double plnPerEurRate) {
        double eur = priceInPln / plnPerEurRate;
        return roundToNearestTenCents(eur);
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
