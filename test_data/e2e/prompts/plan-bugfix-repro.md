Bug report `docs/BUG-142.md`: a member who also enters a coupon gets the discount twice.

Plan the fix, test first: the first change must be a test that fails because of this bug, and only then the code change. Keep the plan small; this is one bug, not a refactoring.

New files may go only under `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
