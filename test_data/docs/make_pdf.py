#!/usr/bin/env python3
"""
Generate datasheet.pdf — a tiny, valid, single-page PDF used by manual test T50
to exercise the PDFBox text-extraction path in DocumentationIndexingService.

Stdlib only (no reportlab). Writes a minimal PDF 1.4 with correct cross-reference
offsets so PDFBox parses it cleanly. Re-run to regenerate the committed binary:

    python make_pdf.py
"""
import os

LINES = [
    "Zeta Subsystem Datasheet (fictional test fixture)",
    "",
    "This PDF is invented test content. It exists so a manual test can prove",
    "the model extracted text from an indexed PDF documentation source.",
    "",
    "Hard limits:",
    "  - max payload = 4096 zeta-units",
    "  - retrieval gate default = 0.73",
    "",
    "Unique retrieval marker: REFIO_DOC_NEEDLE{pdf_delta_9X}",
]

OUT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "datasheet.pdf")


def escape(text):
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def build_content_stream():
    parts = ["BT", "/F1 12 Tf", "14 TL", "50 760 Td"]
    first = True
    for line in LINES:
        if first:
            parts.append("(%s) Tj" % escape(line))
            first = False
        else:
            parts.append("T*")
            parts.append("(%s) Tj" % escape(line))
    parts.append("ET")
    return "\n".join(parts)


def build_pdf():
    content = build_content_stream()
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length %d >>\nstream\n%s\nendstream" % (len(content.encode("latin-1")), content),
    ]

    out = bytearray()
    out += b"%PDF-1.4\n"
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += ("%d 0 obj\n%s\nendobj\n" % (i, body)).encode("latin-1")

    xref_pos = len(out)
    n = len(objects) + 1
    out += ("xref\n0 %d\n" % n).encode("latin-1")
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += ("%010d 00000 n \n" % off).encode("latin-1")
    out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (n, xref_pos)).encode("latin-1")
    return bytes(out)


def main():
    data = build_pdf()
    with open(OUT_PATH, "wb") as fh:
        fh.write(data)
    print("wrote %s (%d bytes)" % (OUT_PATH, len(data)))


if __name__ == "__main__":
    main()
