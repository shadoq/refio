'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { TokenBucket } = require('./token-bucket');

function fakeClock(start = 0) {
  const clock = () => clock.now;
  clock.now = start;
  clock.advance = (ms) => {
    clock.now += ms;
  };
  return clock;
}

function make(options = {}) {
  const clock = options.clock || fakeClock();
  const bucket = new TokenBucket({ capacity: 5, refillPerSecond: 4, clock, ...options });
  return { bucket, clock };
}

test('a new bucket is full by default', () => {
  const { bucket } = make();
  assert.equal(bucket.available(), 5);
  assert.equal(bucket.tryTake(5), true);
  assert.equal(bucket.tryTake(1), false);
});

test('initial sets the starting tokens, also fractional ones', () => {
  assert.equal(make({ initial: 2 }).bucket.available(), 2);
  assert.equal(make({ initial: 0.25 }).bucket.available(), 0.25);
  assert.equal(make({ initial: 0 }).bucket.available(), 0);
});

test('tryTake takes one token by default', () => {
  const { bucket } = make();
  assert.equal(bucket.tryTake(), true);
  assert.equal(bucket.available(), 4);
});

test('a failed tryTake takes nothing', () => {
  const { bucket } = make({ initial: 2 });
  assert.equal(bucket.tryTake(3), false);
  assert.equal(bucket.available(), 2);
  assert.equal(bucket.tryTake(2), true);
});

test('tokens come back with the clock', () => {
  const { bucket, clock } = make({ initial: 0 });
  assert.equal(bucket.tryTake(), false);
  clock.advance(250);
  assert.equal(bucket.tryTake(), true);
  assert.equal(bucket.tryTake(), false);
});

test('refill is fractional and a failing call does not lose the fraction', () => {
  const { bucket, clock } = make({ initial: 0 });
  clock.advance(125);
  assert.equal(bucket.available(), 0.5);
  assert.equal(bucket.tryTake(), false);
  clock.advance(125);
  assert.equal(bucket.tryTake(), true);
});

test('a slow refill rate of half a token per second', () => {
  const { bucket, clock } = make({ initial: 0, refillPerSecond: 0.5 });
  clock.advance(1000);
  assert.equal(bucket.available(), 0.5);
  assert.equal(bucket.tryTake(), false);
  clock.advance(1000);
  assert.equal(bucket.tryTake(), true);
});

test('the bucket never holds more than its capacity', () => {
  const { bucket, clock } = make({ initial: 0 });
  clock.advance(10000);
  assert.equal(bucket.available(), 5);
  assert.equal(bucket.tryTake(5), true);
  assert.equal(bucket.tryTake(), false);
});

test('time spent full is not banked', () => {
  const { bucket, clock } = make({ capacity: 2 });
  clock.advance(100000);
  assert.equal(bucket.tryTake(2), true);
  assert.equal(bucket.tryTake(1), false);
  clock.advance(250);
  assert.equal(bucket.tryTake(1), true);
  assert.equal(bucket.tryTake(1), false);
});

test('a partial refill on top of a nearly full bucket is clamped', () => {
  const { bucket, clock } = make({ initial: 4.5 });
  clock.advance(250);
  assert.equal(bucket.available(), 5);
});

test('asking for more than the capacity is simply refused', () => {
  const { bucket } = make();
  assert.equal(bucket.tryTake(6), false);
  assert.equal(bucket.available(), 5);
});

test('waitTime is zero when the tokens are there and takes nothing', () => {
  const { bucket } = make();
  assert.equal(bucket.waitTime(5), 0);
  assert.equal(bucket.waitTime(), 0);
  assert.equal(bucket.available(), 5);
  assert.equal(bucket.tryTake(5), true);
});

test('waitTime in milliseconds for an empty bucket', () => {
  const { bucket, clock } = make({ initial: 0, refillPerSecond: 2 });
  assert.equal(bucket.waitTime(), 500);
  assert.equal(bucket.waitTime(3), 1500);
  clock.advance(250);
  assert.equal(bucket.waitTime(), 250);
});

test('waitTime accounts for fractional tokens already there', () => {
  const { bucket, clock } = make({ initial: 0 });
  clock.advance(125);
  assert.equal(bucket.waitTime(), 125);
  assert.equal(bucket.waitTime(2), 375);
});

test('waitTime of a slow bucket', () => {
  const { bucket } = make({ initial: 0, refillPerSecond: 0.5 });
  assert.equal(bucket.waitTime(), 2000);
});

test('waitTime rounds up to a whole millisecond, and that wait is enough', () => {
  const { bucket, clock } = make({ initial: 0, refillPerSecond: 3 });
  const wait = bucket.waitTime();
  assert.equal(wait, 334);
  clock.advance(wait - 1);
  assert.equal(bucket.tryTake(), false);
  clock.advance(1);
  assert.equal(bucket.tryTake(), true);
});

test('waitTime is Infinity for more than the capacity', () => {
  const { bucket } = make();
  assert.equal(bucket.waitTime(6), Infinity);
});

test('a clock that goes backwards adds nothing and resets the reference', () => {
  const clock = fakeClock(1000);
  const { bucket } = make({ initial: 0, clock });
  clock.now = 1250;
  assert.equal(bucket.available(), 1);
  clock.now = 500;
  assert.equal(bucket.available(), 1);
  assert.equal(bucket.tryTake(), true);
  clock.now = 625;
  assert.equal(bucket.tryTake(), false);
  clock.now = 750;
  assert.equal(bucket.tryTake(), true);
});

test('buckets are independent of each other', () => {
  const clock = fakeClock();
  const a = new TokenBucket({ capacity: 1, refillPerSecond: 1, clock });
  const b = new TokenBucket({ capacity: 1, refillPerSecond: 1, clock });
  assert.equal(a.tryTake(), true);
  assert.equal(b.tryTake(), true);
  assert.equal(a.tryTake(), false);
});

test('the constructor rejects an invalid capacity', () => {
  const clock = fakeClock();
  for (const capacity of [0, -1, 1.5, '5', NaN, Infinity, undefined, null]) {
    assert.throws(() => new TokenBucket({ capacity, refillPerSecond: 1, clock }), RangeError);
  }
});

test('the constructor rejects an invalid refill rate', () => {
  const clock = fakeClock();
  for (const refillPerSecond of [0, -1, NaN, Infinity, '2', undefined, null]) {
    assert.throws(() => new TokenBucket({ capacity: 1, refillPerSecond, clock }), RangeError);
  }
});

test('the constructor rejects a clock that is not a function', () => {
  for (const clock of [undefined, null, 5, '1']) {
    assert.throws(() => new TokenBucket({ capacity: 1, refillPerSecond: 1, clock }), TypeError);
  }
});

test('the constructor rejects an invalid initial amount', () => {
  const clock = fakeClock();
  for (const initial of [-1, 2.5, NaN, '1', null]) {
    assert.throws(() => new TokenBucket({ capacity: 2, refillPerSecond: 1, clock, initial }), RangeError);
  }
});

test('no options at all is an error', () => {
  assert.throws(() => new TokenBucket(), Error);
});

test('tryTake and waitTime reject counts that are not positive integers', () => {
  const { bucket } = make();
  for (const n of [0, -1, 1.5, NaN, Infinity, '2', null]) {
    assert.throws(() => bucket.tryTake(n), RangeError, `tryTake(${String(n)})`);
    assert.throws(() => bucket.waitTime(n), RangeError, `waitTime(${String(n)})`);
  }
  assert.equal(bucket.available(), 5);
});
