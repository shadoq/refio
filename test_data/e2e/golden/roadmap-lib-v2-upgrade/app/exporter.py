"""Export items for the warehouse system."""
from recordio import open_writer

COLUMNS = ["sku", "name", "qty"]


def export_tsv(items, path):
    """Write sku, name, qty as tab separated text. Items may carry other keys; they are not exported."""
    with open_writer(path, columns=COLUMNS, sep="\t") as writer:
        for item in items:
            writer.write({k: item[k] for k in COLUMNS if k in item})
