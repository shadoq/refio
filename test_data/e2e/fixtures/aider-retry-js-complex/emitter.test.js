const test = require('node:test');
const assert = require('node:assert/strict');
const { Emitter } = require('./emitter');

test('listeners run in order with the emit arguments', () => {
  const em = new Emitter();
  const log = [];
  em.on('a', (x, y) => log.push(`1:${x}${y}`));
  em.on('a', (x, y) => log.push(`2:${x}${y}`));
  assert.equal(em.emit('a', 'p', 'q'), 2);
  assert.deepEqual(log, ['1:pq', '2:pq']);
});

test('emit without listeners returns zero', () => {
  const em = new Emitter();
  assert.equal(em.emit('nothing', 1), 0);
  assert.equal(em.listenerCount('nothing'), 0);
});

test('listeners are called with the emitter as this', () => {
  const em = new Emitter();
  let seen;
  em.on('a', function () { seen = this; });
  em.emit('a');
  assert.equal(seen, em);
});

test('events are independent', () => {
  const em = new Emitter();
  const log = [];
  em.on('a', () => log.push('a'));
  em.on('b', () => log.push('b'));
  em.emit('b');
  assert.deepEqual(log, ['b']);
  assert.equal(em.listenerCount('a'), 1);
});

test('once runs a single time', () => {
  const em = new Emitter();
  let n = 0;
  em.once('a', () => { n++; });
  assert.equal(em.emit('a'), 1);
  assert.equal(em.emit('a'), 0);
  assert.equal(n, 1);
  assert.equal(em.listenerCount('a'), 0);
});

test('a once listener that emits the same event does not run twice', () => {
  const em = new Emitter();
  let n = 0;
  em.once('a', () => { n++; em.emit('a'); });
  em.emit('a');
  assert.equal(n, 1);
});

test('off removes only the most recent registration of a function', () => {
  const em = new Emitter();
  const log = [];
  const f = () => log.push('f');
  em.on('a', f);
  em.on('a', () => log.push('g'));
  em.on('a', f);
  em.off('a', f);
  assert.equal(em.listenerCount('a'), 2);
  em.emit('a');
  assert.deepEqual(log, ['f', 'g']);
});

test('off for an unknown function or event does nothing', () => {
  const em = new Emitter();
  const f = () => {};
  em.off('a', f);
  em.on('a', f);
  em.off('a', () => {});
  em.off('b', f);
  assert.equal(em.listenerCount('a'), 1);
});

test('off also removes a once registration made with the original function', () => {
  const em = new Emitter();
  let n = 0;
  const f = () => { n++; };
  em.once('a', f);
  em.off('a', f);
  assert.equal(em.emit('a'), 0);
  assert.equal(n, 0);
});

test('unsubscribe functions remove only their own registration and are idempotent', () => {
  const em = new Emitter();
  const f = () => {};
  const off1 = em.on('a', f);
  em.on('a', f);
  off1();
  off1();
  assert.equal(em.listenerCount('a'), 1);
  const offOnce = em.once('a', f);
  offOnce();
  assert.equal(em.listenerCount('a'), 1);
});

test('a listener removed by an earlier listener still runs in the same emit', () => {
  const em = new Emitter();
  const log = [];
  const second = () => log.push('second');
  em.on('a', () => { log.push('first'); em.off('a', second); });
  em.on('a', second);
  em.on('a', () => log.push('third'));
  assert.equal(em.emit('a'), 3);
  assert.deepEqual(log, ['first', 'second', 'third']);
  log.length = 0;
  em.emit('a');
  assert.deepEqual(log, ['first', 'third']);
});

test('a listener that removes itself does not make the next one skip', () => {
  const em = new Emitter();
  const log = [];
  const off = em.on('a', () => { log.push('self'); off(); });
  em.on('a', () => log.push('next'));
  em.emit('a');
  assert.deepEqual(log, ['self', 'next']);
});

test('a listener added during an emit waits for the next emit', () => {
  const em = new Emitter();
  const log = [];
  em.on('a', () => { log.push('first'); em.on('a', () => log.push('late')); });
  assert.equal(em.emit('a'), 1);
  assert.deepEqual(log, ['first']);
  log.length = 0;
  assert.equal(em.emit('a'), 2);
  assert.deepEqual(log, ['first', 'late']);
});

test('a throwing listener does not stop the others and the first error is rethrown', () => {
  const em = new Emitter();
  const log = [];
  em.on('a', () => { log.push(1); throw new Error('one'); });
  em.on('a', () => { log.push(2); });
  em.on('a', () => { log.push(3); throw new Error('three'); });
  assert.throws(() => em.emit('a'), { message: 'one' });
  assert.deepEqual(log, [1, 2, 3]);
});

test('a once listener that throws is still removed', () => {
  const em = new Emitter();
  em.once('a', () => { throw new Error('boom'); });
  assert.throws(() => em.emit('a'), { message: 'boom' });
  assert.equal(em.emit('a'), 0);
});
