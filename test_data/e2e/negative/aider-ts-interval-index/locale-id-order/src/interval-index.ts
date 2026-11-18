export interface Interval {
  id: string;
  start: number;
  end: number;
}

function byStartThenId(a: Interval, b: Interval): number {
  if (a.start !== b.start) return a.start < b.start ? -1 : 1;
  if (a.id === b.id) return 0;
  return a.id.localeCompare(b.id);
}

export class IntervalIndex {
  #items = new Map<string, Interval>();

  add(id: string, start: number, end: number): void {
    if (!Number.isFinite(start) || !Number.isFinite(end) || start > end) {
      throw new RangeError(`invalid interval: ${start}..${end}`);
    }
    if (this.#items.has(id)) {
      throw new Error(`duplicate id: ${id}`);
    }
    this.#items.set(id, { id, start, end });
  }

  remove(id: string): boolean {
    return this.#items.delete(id);
  }

  get size(): number {
    return this.#items.size;
  }

  queryPoint(x: number): Interval[] {
    return this.queryRange(x, x);
  }

  queryRange(lo: number, hi: number): Interval[] {
    if (!Number.isFinite(lo) || !Number.isFinite(hi) || lo > hi) {
      throw new RangeError(`invalid range: ${lo}..${hi}`);
    }
    const found: Interval[] = [];
    for (const item of this.#items.values()) {
      if (item.start <= hi && item.end >= lo) {
        found.push({ id: item.id, start: item.start, end: item.end });
      }
    }
    return found.sort(byStartThenId);
  }
}
