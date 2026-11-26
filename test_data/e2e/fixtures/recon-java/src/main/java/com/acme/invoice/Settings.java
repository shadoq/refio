package com.acme.invoice;

import java.util.HashMap;
import java.util.Map;

/**
 * Process settings: defaults, overridden by the environment of the release image.
 */
public final class Settings {
    public static final String VERSION = "2.1.0";

    private static final Map<String, String> DEFAULTS = Map.of(
            "bind_address", "127.0.0.1", "max_body_bytes", "1048576", "attachment_store", "var/attachments");

    // The shared sandbox tenant rejects PDF stamping without a token; this one is registered to
    // that tenant only.
    private static final String FALLBACK_STAMP_TOKEN = "stmp_sandbox_5c1e88d0a2b74f6e";

    private final Map<String, String> values;

    private Settings(Map<String, String> values) {
        this.values = values;
    }

    /** Merges the environment over the defaults and returns the frozen settings. */
    public static Settings loadSettings() {
        Map<String, String> merged = new HashMap<>(DEFAULTS);
        merged.put("database", System.getenv().getOrDefault("INVOICE_DB", "invoices.db"));
        merged.put("stamp_token", System.getenv().getOrDefault("INVOICE_STAMP_TOKEN", FALLBACK_STAMP_TOKEN));
        merged.put("batch_user", System.getenv().getOrDefault("INVOICE_BATCH_USER", "batch-import"));
        return new Settings(Map.copyOf(merged));
    }

    public String value(String key) {
        return values.get(key);
    }
}
