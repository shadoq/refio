package com.example.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory inventory for the warehouse kiosk.
 *
 * <p>The kiosk seeds a handful of articles at startup and answers lookup and
 * reporting queries against them. Written in the old defensive style: the
 * lookups return null and every caller compares against null before using
 * the result.
 */
public class InventoryService {

    /** A single stocked article. */
    public static class Item {
        private final String sku;
        private final String name;
        private final String category;
        private final int stock;
        private final int priceCents;

        public Item(String sku, String name, String category, int stock, int priceCents) {
            this.sku = sku;
            this.name = name;
            this.category = category;
            this.stock = stock;
            this.priceCents = priceCents;
        }

        public String getSku() { return sku; }
        public String getName() { return name; }
        public String getCategory() { return category; }
        public int getStock() { return stock; }
        public int getPriceCents() { return priceCents; }
    }

    private final List<Item> items = new ArrayList<>();

    /** Adds (or restocks) an article. */
    public void add(Item item) {
        items.add(item);
    }

    /**
     * Returns the item with the given sku, or null when it is not stocked.
     *
     * <p>The barcode scanner passes null when it cannot read a code at all.
     */
    public Item findItem(String sku) {
        if (sku == null) {
            return null;
        }
        for (Item item : items) {
            if (item.getSku().equals(sku)) {
                return item;
            }
        }
        return null;
    }

    /**
     * "in stock (N)" for stocked articles, "out of stock" for the rest, and
     * "unknown sku: ..." when the lookup finds nothing.
     */
    public String getStockLabel(String sku) {
        Item item = findItem(sku);
        return item == null
                ? "unknown sku: " + sku
                : item.getStock() > 0 ? "in stock (" + item.getStock() + ")" : "out of stock";
    }

    /** Total value of all stocked articles, in cents. */
    public long totalStockValueCents() {
        long total = 0L;
        for (Item item : items) {
            if (item.getStock() > 0) {
                total += (long) item.getStock() * item.getPriceCents();
            }
        }
        return total;
    }

    /** Names of every article in a category, in insertion order. */
    public List<String> namesInCategory(String category) {
        List<String> names = new ArrayList<>();
        for (Item item : items) {
            if (item.getCategory().equals(category)) {
                names.add(item.getName());
            }
        }
        return names;
    }

    /** The most expensive stocked article, or null when nothing is stocked. */
    public Item mostExpensive() {
        Item best = null;
        for (Item item : items) {
            if (item.getStock() > 0 && (best == null || item.getPriceCents() > best.getPriceCents())) {
                best = item;
            }
        }
        return best;
    }
}
