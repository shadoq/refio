const { slugify } = require("../../../../src/lib/text");

// The admin panel builds the public url of an article when it is published.
function publicPath(article) {
  return `/a/${article.id}/${slugify(article.title)}`;
}

module.exports = { publicPath };
