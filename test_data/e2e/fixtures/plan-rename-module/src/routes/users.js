'use strict';
const { slugify } = require('../utils/strings');

module.exports = (user) => ({ ...user, url: `/users/${slugify(user.name)}` });
