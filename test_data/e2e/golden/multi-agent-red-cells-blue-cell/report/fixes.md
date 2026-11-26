# blue cell fixes

fixed = /api/notes/<id> | added an ownership check so only the note owner receives the body
fixed = /api/render | html-escape the reflected name before embedding it in the page
fixed = /debug/env | removed the debug endpoint from the external surface (404)
fixed = /metrics | internal telemetry no longer reachable externally (404)
