# Reconnaissance report - invoice-service

Scope: the source in `src` and the staging instance.

## Passive reconnaissance (source review)

passive_finding = CWE-611 | src/main/java/com/acme/invoice/XmlInvoiceReader.java | XmlInvoiceReader.parseInvoice
passive_finding = CWE-502 | src/main/java/com/acme/invoice/SessionCookie.java | SessionCookie.parseSession
passive_finding = CWE-89 | src/main/java/com/acme/invoice/Db.java | Db.searchInvoices
passive_finding = CWE-798 | src/main/java/com/acme/invoice/Settings.java | Settings.loadSettings

- **CWE-611 XML external entity (XXE)** (`XmlInvoiceReader.parseInvoice` in `src/main/java/com/acme/invoice/XmlInvoiceReader.java`): uploaded invoice documents go through a default DocumentBuilderFactory: doctypes and external entities are not disabled

- **CWE-502 Insecure deserialization** (`SessionCookie.parseSession` in `src/main/java/com/acme/invoice/SessionCookie.java`): the session cookie is Base64 decoded and fed to ObjectInputStream.readObject, so the client picks the class

- **CWE-89 SQL injection** (`Db.searchInvoices` in `src/main/java/com/acme/invoice/Db.java`): the search pattern is concatenated into the SQL text and run through a plain Statement instead of a bound parameter

- **CWE-798 Hard-coded credential** (`Settings.loadSettings` in `src/main/java/com/acme/invoice/Settings.java`): a PDF stamping token is compiled into the settings loader as the fallback for the environment

## Active reconnaissance (staging instance)


- `/health` answers `{"service": "invoice-service", "version": "1.9.3", ...}` with a fresh `instance` value, so staging runs behind the source (2.1.0).
- `/robots.txt` disallows `/internal/` and `/backups/`; `/internal/ops-status` is the operations status page that 2.0.0 removed and answers 200 on staging, `/backups/` lists three archives of which the 2026-11-21 one is the newest.
- `/invoices/attachments?name=..` lists the parent directory and names `signing.env`; `/invoices/attachments?name=../signing.env` returns the deployment settings with the signing key on 1.9.3, although 2.1.0 fixed exactly that in `fetchAttachment`: the fix has not been deployed.
- `/admin` answers 403 from the network policy and is out of scope.
