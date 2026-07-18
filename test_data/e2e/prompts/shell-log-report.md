The file `logs/access.log` contains one request per line in the format
`<ip> <method> <path> <status>`.

Using shell commands, count how many requests have status code 500 (the last field) and
write a file `report.txt` in the project root containing exactly one line:

    errors=<count>

Nothing else may be in the file. Do not modify `logs/access.log`.
