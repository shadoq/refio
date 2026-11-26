package com.acme.invoice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Entry point: builds the components and routes every request to them. */
public final class Main {
    private final Db db;
    private final Settings settings;
    private final XmlInvoiceReader reader = new XmlInvoiceReader();
    private final AttachmentStore attachments;

    private Main(Db db, Settings settings) {
        this.db = db;
        this.settings = settings;
        this.attachments = new AttachmentStore(Paths.get(settings.value("attachment_store")));
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Settings settings = Settings.loadSettings();
        Db db = new Db(settings.value("database"));
        db.migrate();
        HttpServer server = HttpServer.create(new InetSocketAddress(settings.value("bind_address"), port), 64);
        Main app = new Main(db, settings);
        server.createContext("/health", app::health);
        server.createContext("/invoices", app::dispatch);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("invoice-service " + Settings.VERSION + " listening on port " + port);
    }

    private void health(HttpExchange exchange) throws IOException {
        send(exchange, 200, "application/json", "{\"status\":\"ok\",\"version\":\"" + Settings.VERSION + "\"}");
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String action = exchange.getRequestURI().getPath().substring("/invoices".length()).replace("/", "");
        try {
            switch (action) {
                case "search" -> send(exchange, 200, "text/html",
                        InvoiceRender.list(db.searchInvoices(customerId(exchange), query.getOrDefault("q", ""))));
                case "lookup" -> {
                    List<Invoice> hits = db.findInvoice(customerId(exchange), query.getOrDefault("number", ""))
                            .map(List::of).orElseGet(List::of);
                    send(exchange, hits.isEmpty() ? 404 : 200, "text/html", InvoiceRender.list(hits));
                }
                case "import" -> {
                    Invoice invoice = reader.parseInvoice(exchange.getRequestBody());
                    db.insert(invoice, customerId(exchange));
                    send(exchange, 201, "text/plain", "stored " + invoice.number());
                }
                case "attachments" -> {
                    AttachmentStore.Response response = attachments.fetchAttachment(query.getOrDefault("name", ""));
                    send(exchange, response.status(), response.contentType(), new String(response.body(), StandardCharsets.UTF_8));
                }
                default -> send(exchange, 404, "text/html", InvoiceRender.error(404, "no such page"));
            }
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "text/html", InvoiceRender.error(400, e.getMessage()));
        } catch (RuntimeException e) {
            e.printStackTrace();
            send(exchange, 500, "text/html", InvoiceRender.error(500, "something went wrong"));
        }
    }

    /** The customer id in the session cookie; 0 when there is no readable session (the caller answered 401). */
    private long customerId(HttpExchange exchange) throws IOException {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String value = "";
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("INVOICE_SESSION")) {
                    value = pair[1];
                }
            }
        }
        Long customerId = SessionCookie.parseSession(value);
        if (customerId == null) {
            send(exchange, 401, "text/html", InvoiceRender.error(401, "sign in first"));
        }
        return customerId == null ? 0 : customerId;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> query = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                query.putIfAbsent(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return query;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }
}
