-- Five questions about the bike rental database (see DATA_NOTES.md for the data quirks and build_db.py for the schema).
-- Write one SQLite SELECT statement under each "-- name:" block, below its question.
-- Keep the "-- name:" and "-- question:" lines exactly as they are.

-- name: q1_completed_per_station
-- question: How many completed rentals started from each station? Two columns: station (cleaned) and the number of rentals. Highest number first, ties broken alphabetically by station.
SELECT TRIM(b.station) AS station, COUNT(*) AS n
FROM (SELECT DISTINCT id, bike_id, status FROM rentals) r
JOIN bikes b ON b.id = r.bike_id
WHERE LOWER(TRIM(r.status)) = 'completed'
GROUP BY TRIM(b.station)
ORDER BY n DESC, station;

-- name: q2_average_minutes_per_model
-- question: What is the average duration in minutes of completed rentals per bike model, rounded to 1 decimal? Use only rentals with a known duration and list only models that have at least 2 such rentals. Two columns: model and the average, ordered by model.
SELECT b.model, ROUND(AVG(r.minutes), 1) AS avg_minutes
FROM (SELECT DISTINCT id, bike_id, status, minutes FROM rentals) r
JOIN bikes b ON b.id = r.bike_id
WHERE LOWER(TRIM(r.status)) = 'completed' AND r.minutes IS NOT NULL
GROUP BY b.model
HAVING COUNT(*) >= 2
ORDER BY b.model;

-- name: q3_members_joined_2023
-- question: Which members joined in the year 2023? One column: the member name trimmed and lowercased. Every member once, alphabetical order.
SELECT DISTINCT LOWER(TRIM(full_name)) AS name
FROM members
WHERE joined LIKE '2023-%'
ORDER BY name;

-- name: q4_fees_per_month
-- question: What is the total fee in cents of completed rentals per month of the rental start date? Two columns: month as text YYYY-MM and the total fee in cents. Ordered by month.
SELECT substr(start_date, 1, 7) AS month, SUM(COALESCE(fee_cents, 0)) AS total_fee_cents
FROM (SELECT DISTINCT id, start_date, status, fee_cents FROM rentals)
WHERE LOWER(TRIM(status)) = 'completed' AND start_date LIKE '____-__-__'
GROUP BY month
ORDER BY month;

-- name: q5_most_active_member
-- question: Which member has the most completed rentals? One row with two columns: the member name trimmed and lowercased, and the number of completed rentals. Ties are broken alphabetically by name.
SELECT LOWER(TRIM(m.full_name)) AS name, COUNT(*) AS n
FROM (SELECT DISTINCT id, member_id, status FROM rentals) r
JOIN (SELECT DISTINCT id, full_name FROM members) m ON m.id = r.member_id
WHERE LOWER(TRIM(r.status)) = 'completed'
GROUP BY m.id
ORDER BY n DESC, name
LIMIT 1;
