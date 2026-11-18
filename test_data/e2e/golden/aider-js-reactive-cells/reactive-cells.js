'use strict';

let nextId = 0;
let propagating = false;

class Cell {
  constructor() {
    this._id = nextId++;
    this._value = undefined;
    this._callbacks = new Set();
    this._dependents = new Set();
  }

  get value() {
    return this._value;
  }

  addCallback(cb) {
    this._callbacks.add(cb);
  }

  removeCallback(cb) {
    this._callbacks.delete(cb);
  }
}

class InputCell extends Cell {
  constructor(value) {
    super();
    this._value = value;
  }

  setValue(newValue) {
    if (propagating) {
      throw new Error('setValue cannot be called from a callback');
    }
    if (Object.is(newValue, this._value)) return;
    this._value = newValue;

    const affected = new Set();
    const visit = (cell) => {
      for (const dependent of cell._dependents) {
        if (!affected.has(dependent)) {
          affected.add(dependent);
          visit(dependent);
        }
      }
    };
    visit(this);

    const changed = new Set([this]);
    const ordered = [...affected].sort((a, b) => a._id - b._id);
    for (const cell of ordered) {
      if (!cell._inputs.some((input) => changed.has(input))) continue;
      const next = cell._fn(...cell._inputs.map((input) => input.value));
      if (!Object.is(next, cell._value)) {
        cell._value = next;
        changed.add(cell);
      }
    }

    propagating = true;
    try {
      const firing = [...changed].sort((a, b) => a._id - b._id);
      for (const cell of firing) {
        for (const cb of [...cell._callbacks]) {
          if (cell._callbacks.has(cb)) cb(cell.value);
        }
      }
    } finally {
      propagating = false;
    }
  }
}

class ComputeCell extends Cell {
  constructor(inputs, fn) {
    super();
    this._inputs = [...inputs];
    this._fn = fn;
    this._value = fn(...this._inputs.map((input) => input.value));
    for (const input of this._inputs) input._dependents.add(this);
  }
}

module.exports = { InputCell, ComputeCell };
