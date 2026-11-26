# invoice-service

A small HTTP service that ACME's accounting team uses to store, search and import customer
invoices. It is plain Java 17 on the built-in `com.sun.net.httpserver` server, with SQLite as the
database; there is no application framework.

## Layout

| Path | Purpose |
| --- | --- |
| `src/main/java/com/acme/invoice/Main.java` | entry point, routing and request handling |
| `src/main/java/com/acme/invoice/Db.java` | SQLite access |
| `src/main/java/com/acme/invoice/XmlInvoiceReader.java` | invoice documents and column mappings |
| `src/main/java/com/acme/invoice/SessionCookie.java` | the session and preferences cookies |
| `src/main/java/com/acme/invoice/AttachmentStore.java` | files attached to an invoice |
| `src/main/java/com/acme/invoice/Settings.java` | process settings |
| `src/main/java/com/acme/invoice/InvoiceRender.java` | HTML pages |
| `docs/threat-model.md` | the categories the security review tracks |

## Building and running it

```
mvn package
java -jar target/invoice-service-2.1.0.jar 8080
```

The current release is in `pom.xml` (`<version>`); `src/CHANGELOG.md` lists what each release
changed.

The staging deployment runs an older build of this service and is managed by the release team;
it is not part of this checkout.
