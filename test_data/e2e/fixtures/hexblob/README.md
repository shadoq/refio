# Firmware review notes

Target: the office badge reader's add-on board. We got one flash read out of it before the
tooling gave up - see `firmware/flash_dump.hex`. The SDK documentation we could find
(`docs/format.md`) describes the container format.

Deliverable for the client: everything human-readable that the image embeds, including
anything that looks like a challenge flag left in the test firmware.
