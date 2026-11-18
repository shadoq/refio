'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parseIni } = require('./ini-parser');

const ini = (...lines) => lines.join('\n');

function errorOf(text) {
  try {
    parseIni(text);
  } catch (e) {
    return e;
  }
  assert.fail('expected parseIni to throw');
}

test('empty text gives empty globals and sections', () => {
  assert.deepEqual(parseIni(''), { globals: {}, sections: {} });
  assert.deepEqual(parseIni('\n  \n\n'), { globals: {}, sections: {} });
});

test('globals, sections and keys', () => {
  const text = ini('title = Demo', '[server]', 'host = example.test', 'port = 8080', '[client]', 'retries = 3');
  assert.deepEqual(parseIni(text), {
    globals: { title: 'Demo' },
    sections: { server: { host: 'example.test', port: '8080' }, client: { retries: '3' } },
  });
});

test('whitespace around keys, values and section names is trimmed', () => {
  const text = ini('  [  my section  ]  ', '   key   =    spaced value   ');
  assert.deepEqual(parseIni(text).sections, { 'my section': { key: 'spaced value' } });
});

test('only the first equals sign splits the line', () => {
  assert.deepEqual(parseIni('a = b = c').globals, { a: 'b = c' });
  assert.deepEqual(parseIni('expr==1').globals, { expr: '=1' });
});

test('empty values and keys with inner spaces', () => {
  assert.deepEqual(parseIni(ini('empty =', 'blank =    ', 'my key = v')).globals, {
    empty: '',
    blank: '',
    'my key': 'v',
  });
});

test('keys are case sensitive and may shadow object builtins', () => {
  assert.deepEqual(parseIni(ini('Key = 1', 'key = 2', 'toString = x', 'constructor = y')).globals, {
    Key: '1',
    key: '2',
    toString: 'x',
    constructor: 'y',
  });
});

test('comment lines are ignored, also when indented', () => {
  const text = ini('; first', '# second', '   ; indented', '\t# tab', 'a = 1');
  assert.deepEqual(parseIni(text), { globals: { a: '1' }, sections: {} });
});

test('inline comments need whitespace before the marker', () => {
  const text = ini(
    'a = 80 ; web',
    'b = x # y',
    'c = http://host/#top',
    'd=#fff',
    'e = b#c',
    'f = ;nothing',
    'g =\t# tabbed',
    'h=;x'
  );
  assert.deepEqual(parseIni(text).globals, {
    a: '80',
    b: 'x',
    c: 'http://host/#top',
    d: '#fff',
    e: 'b#c',
    f: '',
    g: '',
    h: ';x',
  });
});

test('section headers may carry a comment', () => {
  assert.deepEqual(parseIni(ini('[a] ; first', 'x = 1', '[b]# second', 'y = 2')).sections, {
    a: { x: '1' },
    b: { y: '2' },
  });
});

test('single quotes are ordinary characters', () => {
  assert.deepEqual(parseIni("q = 'abc'").globals, { q: "'abc'" });
});

test('quoted values keep their whitespace and comment characters', () => {
  const text = ini('a = "  padded  "', 'b = "semi ; hash # inside"', 'c = ""', 'd="x"');
  assert.deepEqual(parseIni(text).globals, { a: '  padded  ', b: 'semi ; hash # inside', c: '', d: 'x' });
});

test('quoted values understand the four escapes', () => {
  const text = ['a = "say \\"hi\\""', 'b = "C:\\\\dir\\\\x"', 'c = "one\\ttwo\\nthree"'].join('\n');
  assert.deepEqual(parseIni(text).globals, { a: 'say "hi"', b: 'C:\\dir\\x', c: 'one\ttwo\nthree' });
});

test('an escaped backslash right before the closing quote does not escape the quote', () => {
  assert.deepEqual(parseIni('a = "end\\\\"').globals, { a: 'end\\' });
});

test('a comment may follow the closing quote', () => {
  assert.deepEqual(parseIni(ini('a = "v" ; note', 'b = "w"# x', 'c = "z"   ')).globals, {
    a: 'v',
    b: 'w',
    c: 'z',
  });
});

test('continuation lines are appended with a newline', () => {
  const text = ini('list = a', '  b', '\tc ; note', 'next = 1');
  assert.deepEqual(parseIni(text).globals, { list: 'a\nb\nc', next: '1' });
});

test('an indented line right after a key line is a continuation even if it looks like a key', () => {
  assert.deepEqual(parseIni(ini('a = 1', '  b = 2')).globals, { a: '1\nb = 2' });
});

test('a continuation of an empty value starts with a newline', () => {
  assert.deepEqual(parseIni(ini('a =', '  b')).globals, { a: '\nb' });
});

test('blank lines, comments and headers end a continuation', () => {
  assert.deepEqual(parseIni(ini('a = 1', '', '  b = 2')).globals, { a: '1', b: '2' });
  assert.deepEqual(parseIni(ini('a = 1', '; c', '  b = 2')).globals, { a: '1', b: '2' });
  assert.deepEqual(parseIni(ini('a = 1', '[s]', '  b = 2')), {
    globals: { a: '1' },
    sections: { s: { b: '2' } },
  });
});

test('an indented key at the start or after a header is an ordinary key', () => {
  assert.deepEqual(parseIni('  x = 1').globals, { x: '1' });
  assert.deepEqual(parseIni(ini('[s]', '    y = 2')).sections, { s: { y: '2' } });
});

test('duplicate keys: the last one wins', () => {
  assert.deepEqual(parseIni(ini('a = 1', 'a = 2', '[s]', 'k = x', 'k = y')), {
    globals: { a: '2' },
    sections: { s: { k: 'y' } },
  });
});

test('a section that appears again is merged', () => {
  const text = ini('[a]', 'x = 1', '[b]', 'y = 1', '[a]', 'z = 2', 'x = 3');
  assert.deepEqual(parseIni(text).sections, { a: { x: '3', z: '2' }, b: { y: '1' } });
});

test('the same key in globals and in a section stays separate', () => {
  assert.deepEqual(parseIni(ini('a = g', '[s]', 'a = s')), { globals: { a: 'g' }, sections: { s: { a: 's' } } });
});

test('windows line endings', () => {
  const text = 'a = 1\r\n[s]\r\nb = "x"\r\nc = 2 ; z\r\n  more\r\n';
  assert.deepEqual(parseIni(text), { globals: { a: '1' }, sections: { s: { b: 'x', c: '2\nmore' } } });
});

test('errors carry the line number in message and property', () => {
  const e = errorOf(ini('a = 1', 'nonsense'));
  assert.ok(e instanceof Error);
  assert.match(e.message, /^line 2: /);
  assert.equal(e.line, 2);
});

test('line numbers count blank and comment lines', () => {
  const e = errorOf('\n\n; c\n\nbad');
  assert.match(e.message, /^line 5: /);
  assert.equal(e.line, 5);
});

test('line numbers count continuation lines', () => {
  const e = errorOf(ini('a = 1', '  more', '  more', 'oops'));
  assert.equal(e.line, 4);
});

test('malformed section headers', () => {
  assert.equal(errorOf(ini('x = 1', '[a')).line, 2);
  assert.equal(errorOf('[]').line, 1);
  assert.equal(errorOf(ini('', '[   ]')).line, 2);
  assert.equal(errorOf('[a] junk').line, 1);
  assert.equal(errorOf('[a]]').line, 1);
});

test('empty key', () => {
  assert.equal(errorOf('= v').line, 1);
  assert.equal(errorOf(ini('[s]', '  = v')).line, 2);
});

test('quoted value errors', () => {
  assert.equal(errorOf('a = "abc').line, 1);
  assert.equal(errorOf('a = "abc\\"').line, 1);
  assert.equal(errorOf(ini('ok = 1', 'a = "a\\q"')).line, 2);
  assert.equal(errorOf('a = "x" y').line, 1);
  assert.equal(errorOf('a = "x""y"').line, 1);
});

test('a quoted value cannot be continued', () => {
  const e = errorOf(ini('a = "x"', '  more'));
  assert.match(e.message, /^line 2: /);
});

test('parsing is a pure function of the text', () => {
  const text = ini('[s]', 'a = 1');
  assert.deepEqual(parseIni(text), parseIni(text));
  assert.notEqual(parseIni(text), parseIni(text));
});
