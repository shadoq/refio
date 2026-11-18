"""Export items for the warehouse system."""
from recordio import write_records


def export_tsv(items, path):
    """Write sku, name, qty as tab separated text. Items may carry other keys; they are not exported."""
    write_records(path, items, ["sku", "name", "qty"], delimiter="\t")
