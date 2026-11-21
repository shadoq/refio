`app/views.py` computes an order total (discount, then tax, then rounding) in two places: `checkout_view` and `invoice_view`. The two copies must stay identical in behaviour.

Plan extracting that calculation into a new module `app/pricing.py` used by both views. The totals must not change by a cent: before moving any code, the current totals have to be pinned by tests.

New files may be only `app/pricing.py` and files under `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
