# Rename customer -> client

The business now says "client", so the code should too.

Done: `shop/clients.py`, `shop/orders.py`.
Still to do: `shop/invoices.py`, `shop/reports.py`.

How I do it: `client_id`, `ClientRecord`, `find_client`, `top_clients`, "Client" and "clients" in printed text, same in docstrings. No compatibility aliases for the old names, nothing should say "customer" in the end.
