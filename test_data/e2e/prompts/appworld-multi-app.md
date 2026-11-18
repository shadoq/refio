I need a briefing for the meetings on 2025-03-12. Three small apps live in `apps/`: `contacts.json` (address book), `calendar.sqlite` (SQLite with `events` and `event_attendees`) and `notes/` (markdown notes, each with a `meeting:` and an `archived:` header line). Please write `out/briefing.json`, a JSON array with one object per meeting, in start-time order:

```
{ "title": "...", "start": "HH:MM", "attendees": [ { "name": "...", "email": "..." } ], "notes": [ "file-name.md" ] }
```

Rules:
- Only meetings on that date that are not cancelled.
- Attendees are people from the address book, sorted by name. The address book has duplicate entries: a contact with `merged_into` set is an old duplicate of that person, so list the person it points to (name and email of the target), and list nobody twice.
- `notes` are the file names of the notes whose `meeting:` matches the meeting title (ignore case) and that are not archived, sorted by file name. Empty list if none.

Do not modify anything under `apps/`. `python checks.py` verifies the output; do not edit it.
