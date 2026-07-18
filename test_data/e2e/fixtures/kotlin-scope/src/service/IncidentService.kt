package com.example.incidents.service

import com.example.incidents.dao.IncidentDao

class IncidentService(private val dao: IncidentDao) {

    // Business layer: it does not read headers; it receives the already-resolved
    // tenant scope and forwards it to the DAO for tenant-filtered queries.
    fun findOpenIncidents(scope: String): List<String> {
        return dao.selectOpenByScope(scope)
    }
}
