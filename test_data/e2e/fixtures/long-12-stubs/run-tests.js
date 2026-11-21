'use strict';
// Runs every tests/*.test.js file and prints one PASS/FAIL line per file plus a summary.
const fs = require('node:fs');
const path = require('node:path');

const dir = path.join(__dirname, 'tests');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.test.js')).sort();
let passed = 0;
for (const file of files) {
    const name = file.replace(/\.test\.js$/, '');
    try {
        require(path.join(dir, file))();
        passed += 1;
        console.log(`PASS ${name}`);
    } catch (err) {
        console.log(`FAIL ${name}: ${String(err && err.message).split('\n')[0]}`);
    }
}
console.log(`SUMMARY passed ${passed}/${files.length}`);
process.exitCode = passed === files.length ? 0 : 1;
