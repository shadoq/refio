const assert = require("node:assert");
const { capitalize } = require("./src/capitalize");

assert.strictEqual(capitalize("hello"), "Hello");
assert.strictEqual(capitalize("world"), "World");

console.log("OK");
