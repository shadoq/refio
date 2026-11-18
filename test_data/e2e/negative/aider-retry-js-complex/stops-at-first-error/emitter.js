class Emitter {
  constructor() {
    this._listeners = new Map();
  }

  _list(event) {
    let list = this._listeners.get(event);
    if (!list) {
      list = [];
      this._listeners.set(event, list);
    }
    return list;
  }

  _add(event, listener, once) {
    const entry = { listener, once, fired: false };
    this._list(event).push(entry);
    return () => this._remove(event, entry);
  }

  _remove(event, entry) {
    const list = this._listeners.get(event);
    if (!list) return;
    const index = list.indexOf(entry);
    if (index >= 0) list.splice(index, 1);
  }

  on(event, listener) {
    return this._add(event, listener, false);
  }

  once(event, listener) {
    return this._add(event, listener, true);
  }

  off(event, listener) {
    const list = this._listeners.get(event);
    if (!list) return;
    for (let i = list.length - 1; i >= 0; i--) {
      if (list[i].listener === listener) {
        list.splice(i, 1);
        return;
      }
    }
  }

  emit(event, ...args) {
    const list = this._listeners.get(event);
    if (!list || list.length === 0) return 0;
    const snapshot = list.slice();
    let called = 0;
    let failed = false;
    let firstError;
    for (const entry of snapshot) {
      if (entry.once) {
        if (entry.fired) continue;
        entry.fired = true;
        this._remove(event, entry);
      }
      called++;
      try {
        entry.listener.apply(this, args);
      } catch (error) {
        throw error;
      }
    }
    if (failed) throw firstError;
    return called;
  }

  listenerCount(event) {
    const list = this._listeners.get(event);
    return list ? list.length : 0;
  }
}

module.exports = { Emitter };
