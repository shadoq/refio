def process_order(order, db, mailer):
    if not order.get("items"):
        raise ValueError("order has no items")
    for item in order["items"]:
        if item["qty"] <= 0:
            raise ValueError("quantity must be positive")
    total = 0
    for item in order["items"]:
        total += item["price"] * item["qty"]
    if total > 200:
        total = total * 0.95
    total = round(total, 2)
    order_id = db.insert("orders", {"customer": order["customer"], "total": total})
    mailer.send(order["customer"], f"Order {order_id} confirmed, total {total}")
    return {"id": order_id, "total": total}
