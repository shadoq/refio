export class CsvError extends Error {
  line: number;
  column: number;

  constructor(message: string, line: number, column: number) {
    super(message);
    this.name = "CsvError";
    this.line = line;
    this.column = column;
  }
}

export interface CsvOptions {
  delimiter?: string;
}

export function parseCsv(text: string, options: CsvOptions = {}): string[][] {
  throw new Error("not implemented");
}
