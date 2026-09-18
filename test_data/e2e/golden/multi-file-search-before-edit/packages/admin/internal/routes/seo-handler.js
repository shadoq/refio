const { slugify } = require("../../../../src/lib/text");

// The longest slug the public site renders without wrapping.
const MAX_SLUG_LENGTH = 12;

// The admin panel builds the public url of an article when it is published.
function publicPath(article) {
  return `/a/${article.id}/${slugify(article.title, MAX_SLUG_LENGTH)}`;
}

module.exports = { publicPath };
