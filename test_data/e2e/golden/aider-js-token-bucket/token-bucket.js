'use strict';

function checkCount(n) {
  if (!Number.isInteger(n) || n <= 0) throw new RangeError('n must be a positive integer');
}

class TokenBucket {
  constructor(options = {}) {
    const { capacity, refillPerSecond, clock, initial = capacity } = options;
    if (!Number.isInteger(capacity) || capacity <= 0) {
      throw new RangeError('capacity must be a positive integer');
    }
    if (typeof refillPerSecond !== 'number' || !Number.isFinite(refillPerSecond) || refillPerSecond <= 0) {
      throw new RangeError('refillPerSecond must be a positive finite number');
    }
    if (typeof clock !== 'function') throw new TypeError('clock must be a function');
    if (typeof initial !== 'number' || !(initial >= 0 && initial <= capacity)) {
      throw new RangeError('initial must be between 0 and capacity');
    }
    this._capacity = capacity;
    this._rate = refillPerSecond;
    this._clock = clock;
    this._tokens = initial;
    this._last = clock();
  }

  _refill() {
    const now = this._clock();
    const elapsed = now - this._last;
    if (elapsed > 0) {
      this._tokens = Math.min(this._capacity, this._tokens + (elapsed * this._rate) / 1000);
    }
    this._last = now;
  }

  available() {
    this._refill();
    return this._tokens;
  }

  tryTake(n = 1) {
    checkCount(n);
    this._refill();
    if (n > this._tokens) return false;
    this._tokens -= n;
    return true;
  }

  waitTime(n = 1) {
    checkCount(n);
    if (n > this._capacity) return Infinity;
    this._refill();
    if (this._tokens >= n) return 0;
    return Math.ceil(((n - this._tokens) * 1000) / this._rate);
  }
}

module.exports = { TokenBucket };
