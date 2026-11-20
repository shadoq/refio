'use strict';

function calcTotal(lines) {
    return lines.reduce((sum, l) => sum + l.price * l.qty, 0);
}

module.exports = { calcTotal };
