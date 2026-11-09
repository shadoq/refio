'use strict';

function romanToInt(s) {
    const v = { I: 1, V: 5, X: 10, L: 50, C: 100, D: 500, M: 1000 };
    if (!/^[IVXLCDM]+$/.test(s)) {
        throw new Error('invalid roman numeral: ' + s);
    }
    let total = 0;
    for (let i = 0; i < s.length; i++) {
        const cur = v[s[i]];
        const next = v[s[i + 1]] || 0;
        total += cur < next ? -cur : cur;
    }
    return total;
}

module.exports = { romanToInt };
