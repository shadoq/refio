'use strict';

function isValidIPv4(s) {
    const parts = s.split('.');
    return parts.length === 4 && parts.every((p) => /^(0|[1-9][0-9]{0,2})$/.test(p) && Number(p) <= 255);
}

module.exports = { isValidIPv4 };
