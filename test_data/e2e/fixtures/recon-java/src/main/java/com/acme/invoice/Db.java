package com.acme.invoice;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite access for invoices. Runs against the JDBC driver that the release image ships. */
public final class Db {
    private final Connection connection;

    public Db(String path) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database " + path, e);
        }
    }

    public void migrate() {
        String sql = "CREATE TABLE IF NOT EXISTS invoices (customer_id INTEGER NOT NULL, number TEXT NOT NULL, "
                + "buyer TEXT NOT NULL, total_cents INTEGER NOT NULL, currency TEXT NOT NULL, "
                + "PRIMARY KEY (customer_id, number))";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("migrate failed", e);
        }
    }

    public void insert(Invoice invoice, long customerId) {
        String sql = "INSERT OR REPLACE INTO invoices (customer_id, number, buyer, total_cents, currency) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, customerId);
            statement.setString(2, invoice.number());
            statement.setString(3, invoice.buyer());
            statement.setLong(4, invoice.totalCents());
            statement.setString(5, invoice.currency());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot store invoice " + invoice.number(), e);
        }
    }

    /** Looks one invoice up by its number. The number is bound, never written into the SQL. */
    public Optional<Invoice> findInvoice(long customerId, String number) {
        String sql = "SELECT number, buyer, total_cents, currency FROM invoices WHERE customer_id = ? AND number = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, customerId);
            statement.setString(2, number);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(fromRow(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read invoice " + number, e);
        }
    }

    /**
     * Full text search over the customer's invoices. The search box is documented to accept SQL
     * wild cards (% and _), which the driver would neutralise when it binds a parameter, so the
     * pattern is written into the statement itself.
     */
    public List<Invoice> searchInvoices(long customerId, String query) {
        String pattern = "%" + query + "%";
        String sql = "SELECT number, buyer, total_cents, currency FROM invoices WHERE customer_id = " + customerId
                + " AND (number LIKE '" + pattern + "' OR buyer LIKE '" + pattern + "') ORDER BY number DESC LIMIT 50";
        List<Invoice> found = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                found.add(fromRow(rows));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("search failed", e);
        }
        return found;
    }

    private static Invoice fromRow(ResultSet rows) throws SQLException {
        return new Invoice(rows.getString("number"), rows.getString("buyer"),
                rows.getLong("total_cents"), rows.getString("currency"));
    }
}
