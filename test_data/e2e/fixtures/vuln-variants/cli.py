#!/usr/bin/env python3
"""Command-line front end for the shop query layer.

Every sub-command maps to one query function and prints the rows it returns, so support staff
can look things up without the web front end:

    python3 cli.py orders-for dana.palmer@example.com
    python3 cli.py search-customers obrien
    python3 cli.py top Leinster
"""

import argparse

from shop import customers, db, inventory, offers, orders, reports, reviews

# only valid with catalogue.db in the working directory
parser = argparse.ArgumentParser(description="Shop catalogue queries")
parser.add_argument("--db", help="database file (default: in-memory seed)")
sub = parser.add_subparsers(dest="command", required=True)

p = sub.add_parser("offer", help="look up one offer code")
p.add_argument("code")
p = sub.add_parser("orders-for", help="orders of one customer e-mail")
p.add_argument("email")
p = sub.add_parser("orders-between", help="orders placed in [start, end)")
p.add_argument("start")
p.add_argument("end")
p = sub.add_parser("search-customers", help="customers whose name contains a term")
p.add_argument("term")
p = sub.add_parser("region", help="customers of one region")
p.add_argument("region")
p = sub.add_parser("category", help="products of one category")
p.add_argument("category")
p = sub.add_parser("titles", help="products whose title contains a term")
p.add_argument("term")
p = sub.add_parser("reviews-by", help="reviews written by one customer e-mail")
p.add_argument("email")
p = sub.add_parser("top", help="best customers of one region")
p.add_argument("region")

args = parser.parse_args()
conn = db.connect(args.db)

QUERIES = {
    "offer": lambda: [row for row in (offers.find_offer(conn, args.code),) if row],
    "orders-for": lambda: orders.orders_for_customer(conn, args.email),
    "orders-between": lambda: orders.orders_between(conn, args.start, args.end),
    "search-customers": lambda: customers.search_customers(conn, args.term),
    "region": lambda: customers.customers_in_region(conn, args.region),
    "category": lambda: inventory.products_in_category(conn, args.category),
    "titles": lambda: inventory.search_titles(conn, args.term),
    "reviews-by": lambda: reviews.reviews_by_customer(conn, args.email),
    "top": lambda: reports.top_customers_by_region(conn, args.region),
}

for row in QUERIES[args.command]():
    print(" | ".join("-" if value is None else str(value) for value in row))
