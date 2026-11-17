Create a complete single self-contained file `calendar_planner_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a calendar planner.

Render month and week grids from real date math with correct weekday alignment, correct month
lengths and leap years, so no impossible dates can appear. Dragging on empty grid space creates an
event spanning the dragged range, and a full edit dialog offers title, start and end times, category
colors and recurrence rules for daily, weekly and monthly patterns with an end condition such as an
end date or occurrence count.

Expand recurring instances correctly across the month, week and agenda views without duplicating the
stored records, and warn visibly when two events overlap in the same slot. An agenda view lists
upcoming events in chronological order.

Navigation includes today, previous and next controls plus a date picker, and a search box filters
events by title. Persist all events to localStorage, seeding demo data on first run only so that
user edits survive reloads and nothing is silently lost. Every visible control must work; no fake
simulation states. Deliver the one file `calendar_planner_model_01.html`.
