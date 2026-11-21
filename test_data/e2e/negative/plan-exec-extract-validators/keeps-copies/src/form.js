'use strict';
const validators = require('./validators');

function isEmail(s) {
    return validators.isEmail(s);
}

function isPhone(s) {
    return validators.isPhone(s);
}

function submit(data) {
    const errors = [];
    if (!isEmail(data.email)) errors.push('email');
    if (!isPhone(data.phone)) errors.push('phone');
    return errors.length ? { ok: false, errors } : { ok: true };
}

module.exports = { submit };
