'use strict';
const { isEmail, isPhone } = require('./validators');

function submit(data) {
    const errors = [];
    if (!isEmail(data.email)) errors.push('email');
    if (!isPhone(data.phone)) errors.push('phone');
    return errors.length ? { ok: false, errors } : { ok: true };
}

module.exports = { submit };
