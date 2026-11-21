'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { applyPatch } = require('./json-patch');

const patch = (doc, ...ops) => applyPatch(doc, ops);

function failure(doc, ...ops) {
  try {
    applyPatch(doc, ops);
  } catch (e) {
    return e;
  }
  assert.fail('expected applyPatch to throw');
}

test('no operations returns an equal but separate document', () => {
  const doc = { a: [1, 2] };
  const out = patch(doc);
  assert.deepEqual(out, doc);
  assert.notEqual(out, doc);
  assert.notEqual(out.a, doc.a);
  assert.equal(patch(5), 5);
});

test('add sets a new key and replaces an existing one', () => {
  assert.deepEqual(patch({ a: 1 }, { op: 'add', path: '/b', value: 2 }), { a: 1, b: 2 });
  assert.deepEqual(patch({ a: 1 }, { op: 'add', path: '/a', value: 2 }), { a: 2 });
  assert.deepEqual(patch({ a: { b: {} } }, { op: 'add', path: '/a/b/c', value: null }), { a: { b: { c: null } } });
});

test('add into arrays: insert, at the end and with "-"', () => {
  assert.deepEqual(patch([1, 3], { op: 'add', path: '/1', value: 2 }), [1, 2, 3]);
  assert.deepEqual(patch([1, 2], { op: 'add', path: '/2', value: 3 }), [1, 2, 3]);
  assert.deepEqual(patch([1, 2], { op: 'add', path: '/-', value: 3 }), [1, 2, 3]);
  assert.deepEqual(patch({ l: [] }, { op: 'add', path: '/l/-', value: 'x' }, { op: 'add', path: '/l/-', value: 'y' }), {
    l: ['x', 'y'],
  });
  assert.deepEqual(patch([1], { op: 'add', path: '/0', value: 0 }), [0, 1]);
});

test('add with an empty path replaces the whole document', () => {
  assert.deepEqual(patch({ a: 1 }, { op: 'add', path: '', value: [1] }), [1]);
  assert.equal(patch({ a: 1 }, { op: 'add', path: '', value: 'x' }), 'x');
});

test('add errors: missing parent, index beyond the end, scalar parent', () => {
  assert.equal(failure({}, { op: 'add', path: '/a/b', value: 1 }).index, 0);
  assert.equal(failure([1], { op: 'add', path: '/2', value: 1 }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'add', path: '/a/b', value: 1 }).index, 0);
  assert.equal(failure({ a: 'str' }, { op: 'add', path: '/a/0', value: 1 }).index, 0);
});

test('remove an object key and an array element', () => {
  assert.deepEqual(patch({ a: 1, b: 2 }, { op: 'remove', path: '/a' }), { b: 2 });
  assert.deepEqual(patch({ l: [1, 2, 3] }, { op: 'remove', path: '/l/1' }), { l: [1, 3] });
  assert.deepEqual(patch([1, 2, 3], { op: 'remove', path: '/2' }), [1, 2]);
});

test('remove errors: missing, index equal to length, "-", root', () => {
  assert.equal(failure({ a: 1 }, { op: 'remove', path: '/b' }).index, 0);
  assert.equal(failure([1, 2], { op: 'remove', path: '/2' }).index, 0);
  assert.equal(failure([1, 2], { op: 'remove', path: '/-' }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'remove', path: '' }).index, 0);
});

test('replace values, array elements and the root', () => {
  assert.deepEqual(patch({ a: 1 }, { op: 'replace', path: '/a', value: { x: 1 } }), { a: { x: 1 } });
  assert.deepEqual(patch([1, 2], { op: 'replace', path: '/1', value: 9 }), [1, 9]);
  assert.equal(patch({ a: 1 }, { op: 'replace', path: '', value: 7 }), 7);
});

test('replace errors: missing key, index out of range, "-"', () => {
  assert.equal(failure({ a: 1 }, { op: 'replace', path: '/b', value: 1 }).index, 0);
  assert.equal(failure([1], { op: 'replace', path: '/1', value: 1 }).index, 0);
  assert.equal(failure([1], { op: 'replace', path: '/-', value: 1 }).index, 0);
});

test('move between objects and into arrays', () => {
  assert.deepEqual(patch({ a: { x: 1 }, b: {} }, { op: 'move', from: '/a/x', path: '/b/y' }), { a: {}, b: { y: 1 } });
  assert.deepEqual(patch({ l: [1], v: 2 }, { op: 'move', from: '/v', path: '/l/-' }), { l: [1, 2] });
});

test('move within an array: the target index counts after the removal', () => {
  assert.deepEqual(patch([1, 2, 3], { op: 'move', from: '/0', path: '/2' }), [2, 3, 1]);
  assert.deepEqual(patch([1, 2, 3], { op: 'move', from: '/2', path: '/0' }), [3, 1, 2]);
  assert.deepEqual(patch([1, 2, 3], { op: 'move', from: '/0', path: '/-' }), [2, 3, 1]);
});

test('move to the same place changes nothing, move into itself fails', () => {
  assert.deepEqual(patch({ a: { b: 1 } }, { op: 'move', from: '/a', path: '/a' }), { a: { b: 1 } });
  assert.equal(failure({ a: { b: 1 } }, { op: 'move', from: '/a', path: '/a/c' }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'move', from: '', path: '/a' }).index, 0);
});

test('move from a missing place fails', () => {
  assert.equal(failure({ a: 1 }, { op: 'move', from: '/b', path: '/c' }).index, 0);
});

test('a prefix match is by token, not by text', () => {
  assert.deepEqual(patch({ a: 1, ab: 2 }, { op: 'move', from: '/a', path: '/ab2' }), { ab: 2, ab2: 1 });
});

test('copy makes an independent deep copy', () => {
  const out = patch({ a: { x: [1] } }, { op: 'copy', from: '/a', path: '/b' });
  assert.deepEqual(out, { a: { x: [1] }, b: { x: [1] } });
  out.b.x.push(2);
  assert.deepEqual(out.a.x, [1]);
});

test('copy to the end of an array and copy of the whole document', () => {
  assert.deepEqual(patch({ l: [1, 2] }, { op: 'copy', from: '/l/0', path: '/l/-' }), { l: [1, 2, 1] });
  assert.deepEqual(patch({ a: 1 }, { op: 'copy', from: '', path: '/self' }), { a: 1, self: { a: 1 } });
});

test('test passes on deep equality regardless of key order', () => {
  const doc = { a: { x: 1, y: [1, { z: null }] } };
  assert.deepEqual(patch(doc, { op: 'test', path: '/a', value: { y: [1, { z: null }], x: 1 } }), doc);
  assert.deepEqual(patch(doc, { op: 'test', path: '/a/y/1/z', value: null }), doc);
});

test('test fails on a difference, a type mismatch, array order and a missing path', () => {
  const doc = { a: 1, l: [1, 2], o: { k: 1 } };
  assert.equal(failure(doc, { op: 'test', path: '/a', value: 2 }).index, 0);
  assert.equal(failure(doc, { op: 'test', path: '/a', value: '1' }).index, 0);
  assert.equal(failure(doc, { op: 'test', path: '/l', value: [2, 1] }).index, 0);
  assert.equal(failure(doc, { op: 'test', path: '/o', value: { k: 1, extra: 1 } }).index, 0);
  assert.equal(failure(doc, { op: 'test', path: '/o', value: {} }).index, 0);
  assert.equal(failure(doc, { op: 'test', path: '/missing', value: null }).index, 0);
  assert.equal(failure(doc, { op: 'test', path: '/a', value: null }).index, 0);
});

test('a successful test lets later operations run', () => {
  assert.deepEqual(
    patch({ v: 1 }, { op: 'test', path: '/v', value: 1 }, { op: 'replace', path: '/v', value: 2 }),
    { v: 2 }
  );
});

test('pointer escapes: ~1 is a slash and ~0 is a tilde', () => {
  assert.deepEqual(patch({}, { op: 'add', path: '/a~1b', value: 1 }), { 'a/b': 1 });
  assert.deepEqual(patch({}, { op: 'add', path: '/m~0n', value: 1 }), { 'm~n': 1 });
  assert.deepEqual(patch({ 'a/b': 1 }, { op: 'remove', path: '/a~1b' }), {});
  assert.deepEqual(patch({ 'x~/y': 1 }, { op: 'replace', path: '/x~0~1y', value: 2 }), { 'x~/y': 2 });
});

test('~01 is the text ~1, not a slash', () => {
  assert.deepEqual(patch({}, { op: 'add', path: '/~01', value: 1 }), { '~1': 1 });
  assert.deepEqual(patch({ '~1': 5 }, { op: 'test', path: '/~01', value: 5 }), { '~1': 5 });
});

test('the pointer "/" addresses the empty key', () => {
  assert.deepEqual(patch({}, { op: 'add', path: '/', value: 1 }), { '': 1 });
  assert.deepEqual(patch({ '': 1 }, { op: 'remove', path: '/' }), {});
});

test('bad pointers are errors', () => {
  for (const path of ['a', 'a/b', '/a~2', '/a~', '~0']) {
    assert.equal(failure({ a: 1 }, { op: 'add', path, value: 1 }).index, 0, path);
  }
  assert.equal(failure({ a: 1 }, { op: 'move', from: 'a', path: '/b' }).index, 0);
});

test('array index tokens must be plain decimal numbers', () => {
  for (const token of ['01', '-1', '1.0', '1e0', 'x', '', ' 1', '+1']) {
    assert.equal(failure([1, 2, 3], { op: 'replace', path: `/${token}`, value: 0 }).index, 0, token);
  }
  assert.equal(failure([1, 2, 3], { op: 'add', path: '/01', value: 0 }).index, 0);
});

test('numeric-looking tokens on objects are ordinary keys', () => {
  assert.deepEqual(patch({}, { op: 'add', path: '/0', value: 'a' }, { op: 'add', path: '/01', value: 'b' }), {
    0: 'a',
    '01': 'b',
  });
});

test('inherited properties do not exist for the patch', () => {
  assert.equal(failure({}, { op: 'remove', path: '/toString' }).index, 0);
  assert.equal(failure({}, { op: 'replace', path: '/constructor', value: 1 }).index, 0);
  assert.equal(failure({}, { op: 'test', path: '/hasOwnProperty', value: null }).index, 0);
  assert.equal(failure({}, { op: 'move', from: '/valueOf', path: '/x' }).index, 0);
  assert.equal(failure({ a: {} }, { op: 'copy', from: '/a/toString', path: '/x' }).index, 0);
});

test('__proto__ is an ordinary key', () => {
  const out = patch({}, { op: 'add', path: '/__proto__', value: { polluted: true } });
  assert.equal(Object.prototype.hasOwnProperty.call(out, '__proto__'), true);
  assert.equal(out.polluted, undefined);
  assert.equal({}.polluted, undefined);
});

test('a failing operation leaves the original document untouched and names its index', () => {
  const doc = { a: { b: [1, 2] }, c: 1 };
  const snapshot = structuredClone(doc);
  const e = failure(
    doc,
    { op: 'add', path: '/a/b/-', value: 3 },
    { op: 'remove', path: '/c' },
    { op: 'replace', path: '/nope', value: 1 },
    { op: 'add', path: '/late', value: 1 }
  );
  assert.ok(e instanceof Error);
  assert.match(e.message, /^op 2: /);
  assert.equal(e.index, 2);
  assert.deepEqual(doc, snapshot);
});

test('a failing test operation aborts the whole patch', () => {
  const doc = { v: 1 };
  const e = failure(doc, { op: 'replace', path: '/v', value: 2 }, { op: 'test', path: '/v', value: 1 });
  assert.equal(e.index, 1);
  assert.deepEqual(doc, { v: 1 });
});

test('a successful patch does not modify the original either', () => {
  const doc = { a: [1, 2], o: { k: 1 } };
  const snapshot = structuredClone(doc);
  const out = patch(
    doc,
    { op: 'add', path: '/a/0', value: 0 },
    { op: 'remove', path: '/o/k' },
    { op: 'move', from: '/a', path: '/moved' }
  );
  assert.deepEqual(out, { o: {}, moved: [0, 1, 2] });
  assert.deepEqual(doc, snapshot);
});

test('inserted values are copies: later changes to the result do not touch the patch', () => {
  const value = { list: [1] };
  const ops = [{ op: 'add', path: '/x', value }];
  const out = applyPatch({}, ops);
  out.x.list.push(2);
  assert.deepEqual(value, { list: [1] });
  assert.deepEqual(ops, [{ op: 'add', path: '/x', value: { list: [1] } }]);
});

test('invalid operations fail with their index', () => {
  assert.equal(failure({}, null).index, 0);
  assert.equal(failure({}, 'add').index, 0);
  assert.equal(failure({}, { op: 'merge', path: '/a', value: 1 }).index, 0);
  assert.equal(failure({}, { path: '/a', value: 1 }).index, 0);
  assert.equal(failure({}, { op: 'add', value: 1 }).index, 0);
  assert.equal(failure({}, { op: 'add', path: 5, value: 1 }).index, 0);
  assert.equal(failure({}, { op: 'add', path: '/a' }).index, 0);
  assert.equal(failure({}, { op: 'replace', path: '' }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'test', path: '/a' }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'move', path: '/b' }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'copy', from: 5, path: '/b' }).index, 0);
  assert.equal(failure({ a: 1 }, { op: 'add', path: '/ok', value: 1 }, { op: 'nope', path: '/a' }).index, 1);
});

test('null is a legal value to add, replace and test', () => {
  assert.deepEqual(patch({ a: 1 }, { op: 'add', path: '/n', value: null }, { op: 'replace', path: '/a', value: null }), {
    a: null,
    n: null,
  });
  assert.deepEqual(patch({ a: null }, { op: 'test', path: '/a', value: null }), { a: null });
});
