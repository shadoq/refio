'use strict';

function isEmail(s) {
    return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(s);
}

function isPhone(s) {
    return s.replace(/[^0-9]/g, '').length >= 9;
}

function submit(data) {
    const errors = [];
    if (!isEmail(data.email)) errors.push('email');
    if (!isPhone(data.phone)) errors.push('phone');
    return errors.length ? { ok: false, errors } : { ok: true };
}

module.exports = { submit };
