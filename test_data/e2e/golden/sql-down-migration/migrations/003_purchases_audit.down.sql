DROP VIEW v_customer_totals;
DROP TABLE audit_log;
ALTER TABLE purchases DROP COLUMN status;
ALTER TABLE purchases RENAME TO orders;
