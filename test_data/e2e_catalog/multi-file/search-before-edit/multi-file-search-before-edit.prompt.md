`slugify` in `src/lib/text.js` always returns the whole slug. The public site cannot render slugs longer than a set limit.

Give `slugify` an optional second argument: a maximum length. When it is given, cut the slug to that length and make sure it does not end with a dash. When it is not given, behave exactly as today.

Then update every place in this repository that should pass a limit. There is a caller outside `src/` that needs it; the site's limit is 12 characters and belongs next to that caller, not inside the helper.

Do not change the tests. Run the suite yourself before you finish.
