Create a complete single self-contained file `habit_tracker_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a habit tracker.

Habits are managed with full CRUD: each habit has a name, a color, an icon or emoji, and a weekly
schedule of target days (which weekdays the habit is due). Each habit may also declare a weekly goal,
such as three completions per week, shown as a progress bar for the current week.

Show a week grid of the habits as rows and the seven days of the current week as columns; today and
past days can be checked or unchecked by clicking, future days are locked, and prev/next week
navigation with a today button lets the user review and correct earlier weeks. Missed scheduled days
must be visibly distinct from non-scheduled days.

For every habit render a GitHub-style year heatmap on canvas or CSS grid: one column per calendar week
and one row per weekday, built from real date math and the habit's actual completion records, colored
by completion state with a month label axis and a tooltip giving the date and status on hover.

Compute statistics from the schedule, never from naive day counting: current streak and longest
streak measured in scheduled completions (skipping non-target days) that must survive month and year
boundaries, per-habit and per-week completion rates, and a stats dashboard summarizing total
completions, best habit and weekly goal attainment.

Persist all data in localStorage with date-robust serialization: completions keyed by local ISO
yyyy-mm-dd strings so no timezone drift can corrupt the grid or streaks. On first run seed realistic
demo data spanning several months so the heatmap and streaks are populated immediately. Every visible
control must work; no fake simulation states. Deliver the one file `habit_tracker_model_01.html`.
