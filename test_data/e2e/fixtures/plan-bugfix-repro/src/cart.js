'use strict';
const { memberDiscount, couponDiscount } = require('./discount');

function cartTotal(items, { member = false, coupon = null } = {}) {
    let total = items.reduce((sum, i) => sum + i.price * i.qty, 0);
    if (member) total = memberDiscount(total);
    if (coupon) total = couponDiscount(total, coupon);
    return Math.round(total * 100) / 100;
}

module.exports = { cartTotal };
