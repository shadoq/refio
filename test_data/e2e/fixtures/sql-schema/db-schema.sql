-- Idempotent schema for a small order-management database.

CREATE TABLE IF NOT EXISTS customers (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS customers_email_idx ON customers (email);

CREATE TABLE IF NOT EXISTS orders (
    id           BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT      NOT NULL REFERENCES customers (id),
    status       VARCHAR(20) NOT NULL DEFAULT 'NEW',
    total_cents  BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status);

CREATE TABLE IF NOT EXISTS order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES orders (id),
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL
);

CREATE INDEX IF NOT EXISTS order_items_order_id_idx ON order_items (order_id);
