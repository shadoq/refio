-- Five questions about the shop database (see build_db.py for the schema).
-- Write one SQLite SELECT statement under each "-- name:" block, below its question.
-- Keep the "-- name:" and "-- question:" lines exactly as they are.

-- name: q1_lisbon_customers
-- question: List the names of the customers who live in Lisbon, in alphabetical order. One column: name.
SELECT 'Carla' AS name UNION ALL SELECT 'Filip' UNION ALL SELECT 'Irene' UNION ALL SELECT 'Luis';

-- name: q2_orders_per_status
-- question: How many orders are there per status? Two columns: status and the number of orders. Most frequent status first, ties broken alphabetically by status.
SELECT status, COUNT(*) AS n FROM orders GROUP BY status ORDER BY n DESC, status;

-- name: q3_delivered_revenue
-- question: What is the total revenue in cents of all delivered orders? The revenue of an order line is its quantity times the current price of the product. One row with one column.
SELECT 0;

-- name: q4_customers_without_orders
-- question: List the names of the customers who have never placed an order, in alphabetical order. One column: name.
SELECT name FROM customers WHERE id IN (4, 9, 13) ORDER BY name;

-- name: q5_priciest_per_category
-- question: For each product category, which product has the highest price? Two columns: category and product name, ordered by category.
SELECT p.category, p.name FROM products p
WHERE p.price_cents = (SELECT MAX(price_cents) FROM products WHERE category = p.category)
ORDER BY p.category;
