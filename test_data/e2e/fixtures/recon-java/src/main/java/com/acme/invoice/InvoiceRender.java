package com.acme.invoice;

import java.util.List;

/**
 * The small HTML pages the portal answers with. Every value that comes from the database or from
 * the request goes through {@link #escape(String)} before it is written into the page.
 */
public final class InvoiceRender {
    private InvoiceRender() {
    }

    public static String error(int status, String message) {
        return "<html><body><h1>" + status + "</h1><p>" + escape(message) + "</p></body></html>";
    }

    public static String list(List<Invoice> invoices) {
        StringBuilder rows = new StringBuilder();
        for (Invoice invoice : invoices) {
            rows.append("<tr><td>").append(escape(invoice.number()))
                    .append("</td><td>").append(escape(invoice.buyer()))
                    .append("</td><td>").append(escape(invoice.formatTotal()))
                    .append("</td></tr>");
        }
        return "<html><body><table>" + rows + "</table></body></html>";
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
