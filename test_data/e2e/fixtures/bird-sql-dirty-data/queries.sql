-- Five questions about the bike rental database (see DATA_NOTES.md for the data quirks and build_db.py for the schema).
-- Write one SQLite SELECT statement under each "-- name:" block, below its question.
-- Keep the "-- name:" and "-- question:" lines exactly as they are.

-- name: q1_completed_per_station
-- question: How many completed rentals started from each station? Two columns: station (cleaned) and the number of rentals. Highest number first, ties broken alphabetically by station.


-- name: q2_average_minutes_per_model
-- question: What is the average duration in minutes of completed rentals per bike model, rounded to 1 decimal? Use only rentals with a known duration and list only models that have at least 2 such rentals. Two columns: model and the average, ordered by model.


-- name: q3_members_joined_2023
-- question: Which members joined in the year 2023? One column: the member name trimmed and lowercased. Every member once, alphabetical order.


-- name: q4_fees_per_month
-- question: What is the total fee in cents of completed rentals per month of the rental start date? Two columns: month as text YYYY-MM and the total fee in cents. Ordered by month.


-- name: q5_most_active_member
-- question: Which member has the most completed rentals? One row with two columns: the member name trimmed and lowercased, and the number of completed rentals. Ties are broken alphabetically by name.
