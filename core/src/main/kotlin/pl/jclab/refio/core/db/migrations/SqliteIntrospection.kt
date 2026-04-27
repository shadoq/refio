package pl.jclab.refio.core.db.migrations

import java.sql.Connection

internal fun tableExists(jdbc: Connection, name: String): Boolean {
    jdbc.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?"
    ).use { ps ->
        ps.setString(1, name)
        ps.executeQuery().use { rs -> return rs.next() }
    }
}

internal fun columnExists(jdbc: Connection, table: String, column: String): Boolean {
    if (!tableExists(jdbc, table)) return false
    jdbc.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
            while (rs.next()) {
                if (rs.getString("name").equals(column, ignoreCase = true)) return true
            }
        }
    }
    return false
}
