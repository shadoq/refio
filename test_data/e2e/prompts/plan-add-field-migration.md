This is a small user service: `app/models.py` (the `User` record), `app/storage.py` (SQLite access), `app/api.py` (the functions the web layer calls) and the SQL migrations in `migrations/`, applied in file-name order at deploy time.

Plan how to add a required `email` field to users: stored in the `users` table, validated by `create_user` (it must contain `@`), and returned by `user_to_dict`.

Production already has rows in `users` without an email. The plan is executed and deployed one step at a time in its order, and the service must keep working after every step.

New files may go only under `migrations/` (the next migration) and `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
