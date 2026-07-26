Create a complete single self-contained file `website_app_data_observatory_model_01.html` (HTML + CSS + JavaScript
inline; no external libraries, charting packages or backend). Build an interactive data-analysis
workbench.

Let the user paste CSV data, upload CSV or JSON, or generate sample datasets; inspect and edit
inferred column types; handle missing values; sort, filter, search; create calculated columns using
a safe expression subset (do NOT use unrestricted eval); group, aggregate and build pivot summaries;
and export the transformed data.

Implement charts using canvas or SVG only: line, bar, stacked bar, scatter, histogram, heat map and
pie/donut, with tooltips, legends, zoom, selection and responsive resizing. Add a dashboard editor
where panels can be added, moved, resized and removed.

Add descriptive statistics (min, max, mean, median, standard deviation, percentiles, unique and
missing counts, correlations). Support at least 10,000 rows without freezing (use chunked
processing where needed). Persist dashboards and settings via localStorage and support JSON
import/export. Deliver the one file `website_app_data_observatory_model_01.html`.
