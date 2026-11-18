export interface Interval {
  id: string;
  start: number;
  end: number;
}

export class IntervalIndex {
  add(id: string, start: number, end: number): void {
    throw new Error("not implemented");
  }

  remove(id: string): boolean {
    throw new Error("not implemented");
  }

  get size(): number {
    throw new Error("not implemented");
  }

  queryPoint(x: number): Interval[] {
    throw new Error("not implemented");
  }

  queryRange(lo: number, hi: number): Interval[] {
    throw new Error("not implemented");
  }
}
