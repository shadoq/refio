// Knowledge Search - client-side only. The (fake) corpus lives in CORPUS below; nothing
// here ever talks to a server.
"use strict";

var CORPUS = [
  "how to reset your badge pin",
  "visitor wifi network name and password",
  "printing from a personal laptop",
  "booking the small meeting room",
];

function parseQuery() {
  // The query lives in the URL fragment: index.html#q=terms
  var fragment = location.hash.substring(1);
  var params = new URLSearchParams(fragment);
  return params.get("q") || "";
}

function renderCount(n) {
  // Status line is plain text.
  document.getElementById("count").textContent = n + " result(s)";
}

function renderResults(query, hits) {
  var target = document.getElementById("results");
  if (hits.length === 0) {
    // No results: echo the query back so the user sees what was searched.
    target.innerHTML = "<p>No results for <b>" + query + "</b></p>";
  } else {
    var items = hits.map(function (h) { return "<li>" + h + "</li>"; }).join("");
    target.innerHTML = "<ul>" + items + "</ul>";
  }
}

function search(query) {
  var q = query.toLowerCase();
  return CORPUS.filter(function (entry) { return entry.indexOf(q) !== -1; });
}

function main() {
  var query = parseQuery();
  var hits = search(query);
  renderCount(hits.length);
  renderResults(query, hits);
}

window.addEventListener("hashchange", main);
main();
