'use strict';

function formatDate(d) {
    return d.toISOString().slice(0, 10);
}

module.exports = { formatDate };
