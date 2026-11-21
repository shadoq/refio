'use strict';

function capitalize(w) {
    return w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : '';
}

module.exports = { capitalize };
