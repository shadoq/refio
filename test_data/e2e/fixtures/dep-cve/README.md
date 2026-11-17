# orders-service

Small Express service (`src/index.js`): groups item tags and logs through pino. The security
team drops an advisory feed at `advisories/local-advisories.json` (the same format the CI
scanner reads) - it lists every dependency of this project that matches a known advisory,
whether or not the code uses it.
