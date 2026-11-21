'use strict';

const ESCAPES = { '"': '"', '\\': '\\', n: '\n', t: '\t' };

function stripInlineComment(s) {
  for (let i = 1; i < s.length; i += 1) {
    if ((s[i] === ';' || s[i] === '#')) return s.slice(0, i);
  }
  return s;
}

function parseIni(text) {
  const result = { globals: {}, sections: {} };
  let target = result.globals;
  let previous = null;

  text.split('\n').forEach((rawLine, index) => {
    const n = index + 1;
    const fail = (message) => {
      const error = new Error(`line ${n}: ${message}`);
      error.line = n;
      throw error;
    };
    const raw = rawLine.replace(/\r$/, '');
    const trimmed = raw.trim();

    if (trimmed === '' || trimmed[0] === ';' || trimmed[0] === '#') {
      previous = null;
      return;
    }

    if (previous && /^[ \t]/.test(raw)) {
      if (previous.quoted) fail('a quoted value cannot be continued');
      previous.target[previous.key] += `\n${stripInlineComment(trimmed).trim()}`;
      return;
    }

    previous = null;

    if (trimmed[0] === '[') {
      const m = /^\[([^\]]*)\]\s*(?:[;#].*)?$/.exec(trimmed);
      if (!m) fail('malformed section header');
      const name = m[1].trim();
      if (name === '') fail('empty section name');
      if (!Object.prototype.hasOwnProperty.call(result.sections, name)) result.sections[name] = {};
      target = result.sections[name];
      return;
    }

    const eq = trimmed.indexOf('=');
    if (eq === -1) fail('expected key = value');
    const key = trimmed.slice(0, eq).trim();
    if (key === '') fail('empty key');
    const rest = trimmed.slice(eq + 1);
    const lead = rest.trimStart();

    if (lead[0] === '"') {
      let value = '';
      let i = 1;
      let closed = false;
      while (i < lead.length) {
        const c = lead[i];
        if (c === '\\') {
          const next = lead[i + 1];
          if (next === undefined || !(next in ESCAPES)) fail(`unknown escape \\${next ?? ''}`);
          value += ESCAPES[next];
          i += 2;
        } else if (c === '"') {
          closed = true;
          i += 1;
          break;
        } else {
          value += c;
          i += 1;
        }
      }
      if (!closed) fail('unterminated quoted value');
      if (!/^\s*(?:[;#].*)?$/.test(lead.slice(i))) fail('unexpected text after quoted value');
      target[key] = value;
      previous = { target, key, quoted: true };
      return;
    }

    target[key] = stripInlineComment(rest).trim();
    previous = { target, key, quoted: false };
  });

  return result;
}

module.exports = { parseIni };
