"use strict";

const IRREGULAR = { person: "people", account: "accounts", currency: "currencies" };

function plural(word, count) {
  if (count === 1) {
    return word;
  }
  if (Object.prototype.hasOwnProperty.call(IRREGULAR, word)) {
    return IRREGULAR[word];
  }
  return /(?:s|x|ch|sh)$/i.test(word) ? `${word}es` : `${word}s`;
}

module.exports = { plural };
