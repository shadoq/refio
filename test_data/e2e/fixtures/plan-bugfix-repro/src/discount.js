'use strict';

function memberDiscount(total) {
    return total * 0.9;
}

function couponDiscount(total, coupon) {
    return coupon === 'SAVE10' ? total * 0.9 : total;
}

module.exports = { memberDiscount, couponDiscount };
