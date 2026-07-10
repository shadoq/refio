package com.example.incidents.dao

class IncidentDao {

    private val rows = listOf(
        "acme" to "disk full",
        "acme" to "cpu high",
        "globex" to "network down",
    )

    // Persistence layer: every query is tenant-filtered on the scope value that
    // originated from the X-Scope-OrgID header at the controller.
    fun selectOpenByScope(scope: String): List<String> {
        return rows.filter { it.first == scope }.map { it.second }
    }
}
