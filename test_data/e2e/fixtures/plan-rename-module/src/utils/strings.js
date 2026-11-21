'use strict';

function slugify(s) {
    return s.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

function titleCase(s) {
    return s.replace(/\b\w/g, (c) => c.toUpperCase());
}

module.exports = { slugify, titleCase };
