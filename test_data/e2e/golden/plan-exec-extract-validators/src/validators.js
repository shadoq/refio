'use strict';

function isEmail(s) {
    return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(s);
}

function isPhone(s) {
    return s.replace(/[^0-9]/g, '').length >= 9;
}

module.exports = { isEmail, isPhone };
