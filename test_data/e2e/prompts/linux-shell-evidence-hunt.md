You are doing an offline security review of a captured server file system (a stand-in
project directory: `etc/`, `var/log/`, `home/`, `srv/app/`, `usr/local/bin/`, `opt/vendor/`).

Answer the twelve questions below by examining the tree with the shell (`grep`, `find`,
`ls -la`, `stat`, `wc`, `du`, `python3` one-liners, ...). `opt/vendor/` is the backup
appliance vendor's support bundle: reference and example material only, not live
configuration, so nothing there is ever the answer. Nothing depends on file timestamps
or ownership; use content, names, the executable bit and symlinks only.

Write `report/evidence.txt` with exactly one line per question, in the form
`key = value` (no quotes, no markdown), using these keys and value shapes:

1. `ssh_root_login = <yes|no>` - does any live sshd configuration (the main file and its
   drop-ins under `etc/ssh/sshd_config.d/`) permit root login?
2. `rogue_cron_command = <the command>` - the full command of the cron entry under `etc/`
   that downloads a script from the network (the whole command, nothing else on the line).
3. `leaked_token_file = <path>` - the source file under `srv/app/` with a hard-coded
   credential in it (path relative to the project root).
4. `extra_key_user = <user>` - the user whose `home/<user>/.ssh/authorized_keys` holds
   more than one key.
5. `hidden_file = <path>` - the hidden file (name starting with a dot) that sits in the
   deepest directory under `home/`.
6. `non_executable_script = <path>` - the script under `usr/local/bin/` that has a shebang
   but no executable bit (ignore `opt/vendor/`).
7. `symlink_target = <path>` - what the symlink under `usr/local/bin/` points at
   (the path it names, relative to the project root).
8. `last_error_time = <HH:MM:SS>` - the time of the last ERROR line in
   `var/log/app/application.log`.
9. `top_failed_login_ip = <ip>` - the source ip with the most failed password attempts
   in `var/log/auth.log`.
10. `failed_login_count = <n>` - how many failed password attempts `var/log/auth.log`
    contains in total.
11. `largest_log = <path>` - the largest file under `var/log` measured by size in bytes
    (content size, so the answer survives the tree being copied).
12. `config_with_weak_cipher = <path>` - the live configuration under `etc/` that still
    offers a weak cipher or protocol (SSLv3, RC4 or 3DES); `opt/vendor/` copies do not
    count.

Paths are relative to the project root without a leading `./`. Verify with
`python3 check.py` and iterate until it passes. Do not modify anything except
`report/` and `report/evidence.txt`; `check.py` and every input file must stay
byte-identical.
