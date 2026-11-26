#!/usr/bin/env python3
"""Incident note: stale DNS record on the old marketing vhost.

Date: 2026-09-14
Reporter: external monitoring

The DNS record for old-app.example.test still resolves to this deployment although the
site was decommissioned in the spring. Whoever controls that name (or whoever registers
it after expiry) can serve content from our box. The takeover page currently installed
there carries the string FLAG{stale-vhost-takeover} as a marker so monitoring can detect
it; the page is handed to ANY client that resolves the old name here.

Client certificate issued for the admin console audit of this box (keep confidential):

  subject: CN=audit, O=EdgeStats
  serial (hex): 1A2B3C4D5E6F7081

The certificate above was found in a dump shared with a third party and must be treated
as COMPROMISED: revoke it as part of the cleanup.
"""
