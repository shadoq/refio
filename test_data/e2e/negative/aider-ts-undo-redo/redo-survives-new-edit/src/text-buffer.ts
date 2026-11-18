interface Edit {
  pos: number;
  removed: string;
  inserted: string;
}

interface Step {
  edits: Edit[];
  cursorBefore: number;
  cursorAfter: number;
}

export class TextBuffer {
  #text: string;
  #cursor = 0;
  #undoStack: Step[] = [];
  #redoStack: Step[] = [];
  #depth = 0;
  #open: Step | null = null;

  constructor(initial: string = "") {
    this.#text = initial;
  }

  get text(): string {
    return this.#text;
  }

  get cursor(): number {
    return this.#cursor;
  }

  setCursor(pos: number): void {
    if (!Number.isInteger(pos) || pos < 0 || pos > this.#text.length) {
      throw new RangeError(`cursor out of range: ${pos}`);
    }
    this.#cursor = pos;
  }

  insert(pos: number, str: string): void {
    if (!Number.isInteger(pos) || pos < 0 || pos > this.#text.length) {
      throw new RangeError(`position out of range: ${pos}`);
    }
    if (str.length === 0) return;
    this.#record({ pos, removed: "", inserted: str }, pos + str.length);
  }

  delete(pos: number, length: number): void {
    if (!Number.isInteger(pos) || !Number.isInteger(length) || pos < 0 || length < 0 || pos + length > this.#text.length) {
      throw new RangeError(`range out of bounds: ${pos}, ${length}`);
    }
    if (length === 0) return;
    this.#record({ pos, removed: this.#text.slice(pos, pos + length), inserted: "" }, pos);
  }

  undo(): boolean {
    this.#requireNoGroup();
    const step = this.#undoStack.pop();
    if (!step) return false;
    for (let i = step.edits.length - 1; i >= 0; i--) {
      const e = step.edits[i];
      this.#text = this.#text.slice(0, e.pos) + e.removed + this.#text.slice(e.pos + e.inserted.length);
    }
    this.#cursor = step.cursorBefore;
    this.#redoStack.push(step);
    return true;
  }

  redo(): boolean {
    this.#requireNoGroup();
    const step = this.#redoStack.pop();
    if (!step) return false;
    for (const e of step.edits) {
      this.#text = this.#text.slice(0, e.pos) + e.inserted + this.#text.slice(e.pos + e.removed.length);
    }
    this.#cursor = step.cursorAfter;
    this.#undoStack.push(step);
    return true;
  }

  beginGroup(): void {
    this.#depth++;
  }

  endGroup(): void {
    if (this.#depth === 0) throw new Error("endGroup without beginGroup");
    this.#depth--;
    if (this.#depth === 0 && this.#open) {
      this.#undoStack.push(this.#open);
      this.#open = null;
    }
  }

  #requireNoGroup(): void {
    if (this.#depth > 0) throw new Error("a group is open");
  }

  #record(edit: Edit, cursorAfter: number): void {
    const cursorBefore = this.#cursor;
    this.#text = this.#text.slice(0, edit.pos) + edit.inserted + this.#text.slice(edit.pos + edit.removed.length);
    this.#cursor = cursorAfter;
    if (this.#depth > 0) {
      if (!this.#open) this.#open = { edits: [], cursorBefore, cursorAfter };
      this.#open.edits.push(edit);
      this.#open.cursorAfter = cursorAfter;
    } else {
      this.#undoStack.push({ edits: [edit], cursorBefore, cursorAfter });
    }
  }
}
