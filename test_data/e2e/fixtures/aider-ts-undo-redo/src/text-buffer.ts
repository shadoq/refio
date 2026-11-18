export class TextBuffer {
  constructor(initial: string = "") {
    throw new Error("not implemented");
  }

  get text(): string {
    throw new Error("not implemented");
  }

  get cursor(): number {
    throw new Error("not implemented");
  }

  setCursor(pos: number): void {
    throw new Error("not implemented");
  }

  insert(pos: number, str: string): void {
    throw new Error("not implemented");
  }

  delete(pos: number, length: number): void {
    throw new Error("not implemented");
  }

  undo(): boolean {
    throw new Error("not implemented");
  }

  redo(): boolean {
    throw new Error("not implemented");
  }

  beginGroup(): void {
    throw new Error("not implemented");
  }

  endGroup(): void {
    throw new Error("not implemented");
  }
}
