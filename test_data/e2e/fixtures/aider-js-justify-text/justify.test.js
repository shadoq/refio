'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { justify } = require('./justify');

test('text without words gives no lines', () => {
  assert.deepEqual(justify('', 10), []);
  assert.deepEqual(justify(' \n\t ', 10), []);
});

test('width must be an integer of at least 2', () => {
  for (const width of [1, 0, -5, 2.5, '10', NaN, undefined]) {
    assert.throws(() => justify('abc', width), RangeError);
  }
  assert.doesNotThrow(() => justify('abc', 2));
});

test('a text that fits on one line is just that line, not padded', () => {
  assert.deepEqual(justify('hello world', 20), ['hello world']);
});

test('lines are justified to the width and the last one is left-aligned', () => {
  assert.deepEqual(justify('The quick brown fox jumps over the lazy dog again', 12), [
    'The    quick',
    'brown    fox',
    'jumps   over',
    'the lazy dog',
    'again',
  ]);
});

test('leftover spaces go to the leftmost gaps', () => {
  assert.deepEqual(justify('aa bb cc dd ee', 9), ['aa  bb cc', 'dd ee']);
  assert.deepEqual(justify('a b c d eeeeeeeee', 9), ['a  b  c d', 'eeeeeeeee']);
});

test('even spreading when the spaces divide evenly', () => {
  assert.deepEqual(justify('aa bb cc dd ee', 10), ['aa  bb  cc', 'dd ee']);
});

test('a single-word line is left-aligned wherever it is', () => {
  assert.deepEqual(justify('aaaaaaa bbbbbbb c', 8), ['aaaaaaa', 'bbbbbbb', 'c']);
});

test('the last line keeps single spaces between several words', () => {
  assert.deepEqual(justify('aaaa bbbb c d e', 9), ['aaaa bbbb', 'c d e']);
});

test('a word of exactly the width fits on a line of its own', () => {
  assert.deepEqual(justify('abcdefgh ij', 8), ['abcdefgh', 'ij']);
});

test('whitespace of any kind separates words and the edges are trimmed', () => {
  assert.deepEqual(justify('  hello \n\t  world  ', 20), ['hello world']);
});

test('a word one character too long is split with a hyphen at width - 1', () => {
  assert.deepEqual(justify('abcdefghijk', 10), ['abcdefghi-', 'jk']);
});

test('a very long word is split repeatedly', () => {
  assert.deepEqual(justify('abcdefghijkl', 5), ['abcd-', 'efgh-', 'ijkl']);
});

test('the remainder of a split word may be exactly the width', () => {
  assert.deepEqual(justify('abcdefghi', 5), ['abcd-', 'efghi']);
  assert.deepEqual(justify('abcdefgh', 5), ['abcd-', 'efgh']);
});

test('width 2 is the smallest and still splits', () => {
  assert.deepEqual(justify('abc', 2), ['a-', 'bc']);
  assert.deepEqual(justify('abcd', 2), ['a-', 'b-', 'cd']);
});

test('the line before a long word is closed and justified, the remainder joins the next words', () => {
  assert.deepEqual(justify('ab cd abcdefghijklmnop ef', 10), ['ab      cd', 'abcdefghi-', 'jklmnop ef']);
});

test('a long word right after a full line adds no empty line', () => {
  assert.deepEqual(justify('abcdefgh abcdefghijk', 8), ['abcdefgh', 'abcdefg-', 'hijk']);
});

test('a long word at the start of the text', () => {
  assert.deepEqual(justify('abcdefghijk x y', 6), ['abcde-', 'fghijk', 'x y']);
});

test('a split word in the middle of the text keeps the word order', () => {
  const lines = justify('one two three supercalifragilistic four five six', 12);
  assert.deepEqual(lines, [
    'one      two',
    'three',
    'supercalifr-',
    'agilistic',
    'four    five',
    'six',
  ]);
});

test('every line fits, every full line is exact, and no words are lost', () => {
  const text =
    'Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor ' +
    'incididunt ut labore et dolore magna aliqua';
  const original = text.split(/\s+/);
  for (const width of [8, 10, 11, 17, 30, 80]) {
    const lines = justify(text, width);
    lines.forEach((line, i) => {
      assert.ok(line.length <= width, `width ${width}, line ${i}`);
      assert.equal(line, line.trim(), 'no leading or trailing spaces');
      if (i < lines.length - 1 && line.includes(' ')) assert.equal(line.length, width);
    });
    if (width >= 11) {
      assert.deepEqual(lines.join(' ').split(/\s+/), original);
    }
  }
});
