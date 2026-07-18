package com.example.incidents.controller

import com.example.incidents.service.IncidentService

private const val SCOPE_HEADER = "X-Scope-OrgID"

class IncidentController(private val service: IncidentService) {

    // The tenant scope enters the system here, as an HTTP header, and is passed
    // down explicitly through every layer below.
    fun listIncidents(headers: Map<String, String>): List<String> {
        val scope = headers[SCOPE_HEADER] ?: throw IllegalArgumentException("missing tenant scope")
        return service.findOpenIncidents(scope)
    }
}
