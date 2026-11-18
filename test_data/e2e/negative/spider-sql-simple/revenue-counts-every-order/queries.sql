-- Five questions about the shop database (see build_db.py for the schema).
-- Write one SQLite SELECT statement under each "-- name:" block, below its question.
-- Keep the "-- name:" and "-- question:" lines exactly as they are.

-- name: q1_lisbon_customers
-- question: List the names of the customers who live in Lisbon, in alphabetical order. One column: name.
SELECT name FROM customers WHERE city = 'Lisbon' ORDER BY name;

-- name: q2_orders_per_status
-- question: How many orders are there per status? Two columns: status and the number of orders. Most frequent status first, ties broken alphabetically by status.
SELECT status, COUNT(*) AS n FROM orders GROUP BY status ORDER BY n DESC, status;

-- name: q3_delivered_revenue
-- question: What is the total revenue in cents of all delivered orders? The revenue of an order line is its quantity times the current price of the product. One row with one column.
SELECT COALESCE(SUM(oi.qty * p.price_cents), 0)
FROM order_items oi
JOIN products p ON p.id = oi.product_id;

-- name: q4_customers_without_orders
-- question: List the names of the customers who have never placed an order, in alphabetical order. One column: name.
SELECT c.name FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL
ORDER BY c.name;

-- name: q5_priciest_per_category
-- question: For each product category, which product has the highest price? Two columns: category and product name, ordered by category.
SELECT p.category, p.name FROM products p
WHERE p.price_cents = (SELECT MAX(price_cents) FROM products WHERE category = p.category)
ORDER BY p.category;
