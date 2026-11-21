'use strict';

function isPalindrome(s) {
    const t = s.toLowerCase().replace(/[^a-z0-9]/g, '');
    return t === [...t].reverse().join('');
}

module.exports = { isPalindrome };
