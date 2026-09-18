const test = require("node:test");
const assert = require("node:assert/strict");
const { slugify } = require("../src/lib/text");
const { publicPath } = require("../packages/admin/internal/routes/seo-handler");

test("a slug is cut to the requested length without leaving a trailing dash", () => {
  assert.equal(slugify("Gęśla Jaźń Śpiewa Cicho", 10), "gesla-jazn");
});

test("without a length the slug keeps the whole title", () => {
  assert.equal(slugify("Hello There World"), "hello-there-world");
});

test("every caller passes the shop's own limit, including the admin panel", () => {
  const path = publicPath({ id: 7, title: "A Very Long Article Title Indeed" });
  assert.equal(path, "/a/7/a-very-long");
});
