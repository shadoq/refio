# Change ticket CHG-4711 - deploy account sudo rule

Request from the platform team, 2026-04-02:

The `deploy` account runs the nightly release job and needs to restart the
`app` service without a password prompt. It must not be able to do anything
else as root, and it must not be able to run commands as other users.

## Approved rule

The rule as signed off by the change advisory board is, in full:

    deploy ALL=(deploy) NOPASSWD: /usr/bin/systemctl restart app

One line, exactly as written above, is to be placed in
`etc/sudoers.d/deploy` (replacing any earlier draft of the rule). Nothing
else in the sudo policy changes under this ticket; the main `etc/sudoers`
file is explicitly out of scope.

## Notes

- The `(deploy)` runas specification is deliberate: the release job only
  ever needs to act as the deploy account itself while bouncing the
  service, and the CAB refused a broader runas.
- Only the single systemctl command is whitelisted. No wildcards.
- If what is currently in `etc/sudoers.d/deploy` does not match the rule
  above character for character, the drop-in is wrong and must be corrected
  to match this ticket before the next release window.
