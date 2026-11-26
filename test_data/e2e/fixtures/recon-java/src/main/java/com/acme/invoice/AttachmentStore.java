package com.acme.invoice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Files the customer attached to an invoice (delivery notes, scanned order forms). */
public final class AttachmentStore {
    private final Path root;

    public AttachmentStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Serves one stored attachment. Since 2.1.0 only the file name is used and the resolved path
     * has to stay inside the store.
     */
    public Response fetchAttachment(String name) {
        Path fileName = Paths.get(name).getFileName();
        Path path = fileName == null ? null : root.resolve(fileName).normalize();
        if (path == null || !path.startsWith(root) || !Files.isRegularFile(path)) {
            return new Response(404, "text/plain", ("no such attachment: " + name).getBytes(StandardCharsets.UTF_8));
        }
        try {
            return new Response(200, "application/octet-stream", Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read attachment " + name, e);
        }
    }

    public String storeAttachment(String invoiceNumber, byte[] content) {
        String fileName = invoiceNumber.replaceAll("[^A-Za-z0-9-]", "_") + "-" + content.length + ".bin";
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(fileName), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return fileName;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store attachment", e);
        }
    }

    /** What the handlers send back: status, content type and the raw body. */
    public record Response(int status, String contentType, byte[] body) {
    }
}
