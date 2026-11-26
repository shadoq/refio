#!/usr/bin/env python3
"""Incident note: domain hijack through the gateway's interception layer.

Date: 2026-11-02
Reporter: NOC

The gateway's interception layer (INTERCEPTED_HOSTS in server/app.py) was meant for
throwaway maintenance hosts. During the last rollout api.example.test was added to it,
and since then:

  - requests for Host api.example.test are answered by the interception layer itself
    with a foreign "maintenance portal" page instead of being forwarded to the backend
    (the hijacker's page carries the marker hijacker-wins-7f3d so monitoring can detect it),
  - the interception page ships none of the backend's security headers, and the proxy
    copy drops them too, so clients of api.example.test lost all of them.

Required state after the fix:

  - direct requests for Host api.example.test must reach the BACKEND (the ExampleAPI
    pages, both the proxied public routes /, /status, /api/v1/health and the /internal
    pages) and carry the backend's security headers with their exact values:
    X-Content-Type-Options: nosniff, X-Frame-Options: DENY,
    Strict-Transport-Security: max-age=31536000; includeSubDomains,
  - unknown hosts keep getting the proxy's 404,
  - the hijacker's page (and its marker) must be gone from the gateway for good.
