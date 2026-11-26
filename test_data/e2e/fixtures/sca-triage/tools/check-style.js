"use strict";

// Lint rules shared with the other services of the finance group.
const rules = require("style-rules");

const files = process.argv.slice(2);
const problems = files.flatMap((file) => rules.check(file, { semi: true, quotes: "double" }));
for (const problem of problems) {
  console.error(`${problem.file}:${problem.line}: ${problem.message}`);
}
process.exit(problems.length ? 1 : 0);
