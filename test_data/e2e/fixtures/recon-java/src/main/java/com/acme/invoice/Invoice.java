package com.acme.invoice;

/**
 * One invoice as the database stores it. Amounts are kept in the smallest currency unit.
 */
public record Invoice(String number, String buyer, long totalCents, String currency) {

    public String formatTotal() {
        return String.format("%d.%02d %s", totalCents / 100, Math.abs(totalCents % 100), currency);
    }
}
