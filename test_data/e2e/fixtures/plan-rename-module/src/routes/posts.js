'use strict';
const { slugify } = require('../utils/strings');

module.exports = (post) => ({ ...post, url: `/posts/${slugify(post.title)}` });
