# signup validators

`validators.js` holds the field validation shared by the workshop signup form
and the admin CSV importer: email, birth date (MM/DD/YYYY, as written on the
paper form), URL slug and hex colour. `validateSignup()` aggregates the
required fields into one errors object.

Run the acceptance gate with:

    node checks.js
