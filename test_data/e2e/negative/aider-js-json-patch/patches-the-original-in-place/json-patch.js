'use strict';

const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
const isObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
const INDEX = /^(0|[1-9][0-9]*)$/;

function parsePointer(pointer) {
  if (typeof pointer !== 'string') throw new Error('pointer must be a string');
  if (pointer === '') return [];
  if (pointer[0] !== '/') throw new Error(`pointer must start with "/": ${pointer}`);
  return pointer
    .slice(1)
    .split('/')
    .map((token) => {
      if (/~(?![01])/.test(token)) throw new Error(`bad escape in pointer: ${pointer}`);
      return token.replace(/~1/g, '/').replace(/~0/g, '~');
    });
}

function deepEqual(a, b) {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => deepEqual(x, b[i]));
  }
  if (isObject(a) && isObject(b)) {
    const ka = Object.keys(a);
    return ka.length === Object.keys(b).length && ka.every((k) => hasOwn(b, k) && deepEqual(a[k], b[k]));
  }
  return false;
}

function child(container, token) {
  if (Array.isArray(container)) {
    if (!INDEX.test(token) || Number(token) >= container.length) throw new Error('path not found');
    return container[Number(token)];
  }
  if (isObject(container) && hasOwn(container, token)) return container[token];
  throw new Error('path not found');
}

function get(doc, tokens) {
  return tokens.reduce(child, doc);
}

function setKey(object, key, value) {
  Object.defineProperty(object, key, { value, writable: true, enumerable: true, configurable: true });
}

function add(doc, tokens, value) {
  if (tokens.length === 0) return value;
  const parent = get(doc, tokens.slice(0, -1));
  const key = tokens[tokens.length - 1];
  if (Array.isArray(parent)) {
    if (key === '-') {
      parent.push(value);
    } else {
      if (!INDEX.test(key) || Number(key) > parent.length) throw new Error('index out of range');
      parent.splice(Number(key), 0, value);
    }
  } else if (isObject(parent)) {
    setKey(parent, key, value);
  } else {
    throw new Error('parent is not a container');
  }
  return doc;
}

function remove(doc, tokens) {
  if (tokens.length === 0) throw new Error('cannot remove the whole document');
  const parent = get(doc, tokens.slice(0, -1));
  const key = tokens[tokens.length - 1];
  get(parent, [key]);
  if (Array.isArray(parent)) parent.splice(Number(key), 1);
  else delete parent[key];
  return doc;
}

function replace(doc, tokens, value) {
  if (tokens.length === 0) return value;
  const parent = get(doc, tokens.slice(0, -1));
  const key = tokens[tokens.length - 1];
  get(parent, [key]);
  if (Array.isArray(parent)) parent[Number(key)] = value;
  else setKey(parent, key, value);
  return doc;
}

function applyOne(doc, op) {
  if (!isObject(op)) throw new Error('operation must be an object');
  const path = parsePointer(op.path);
  const needValue = () => {
    if (!hasOwn(op, 'value') || op.value === undefined) throw new Error('missing value');
    return structuredClone(op.value);
  };
  const needFrom = () => {
    if (typeof op.from !== 'string') throw new Error('missing from');
    return parsePointer(op.from);
  };
  switch (op.op) {
    case 'add':
      return add(doc, path, needValue());
    case 'remove':
      return remove(doc, path);
    case 'replace':
      return replace(doc, path, needValue());
    case 'move': {
      const from = needFrom();
      const value = get(doc, from);
      if (from.length < path.length && from.every((t, i) => t === path[i])) {
        throw new Error('cannot move into own child');
      }
      if (from.length === path.length && from.every((t, i) => t === path[i])) return doc;
      return add(remove(doc, from), path, value);
    }
    case 'copy': {
      const from = needFrom();
      return add(doc, path, structuredClone(get(doc, from)));
    }
    case 'test':
      if (!deepEqual(get(doc, path), needValue())) throw new Error('test failed');
      return doc;
    default:
      throw new Error(`unknown op: ${String(op.op)}`);
  }
}

function applyPatch(doc, ops) {
  let result = doc;
  ops.forEach((op, index) => {
    try {
      result = applyOne(result, op);
    } catch (cause) {
      const error = new Error(`op ${index}: ${cause.message}`);
      error.index = index;
      throw error;
    }
  });
  return result;
}

module.exports = { applyPatch };
