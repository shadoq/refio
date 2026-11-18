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
  const delimiter = options.delimiter ?? ",";
  if (delimiter.length !== 1 || delimiter === '"' || delimiter === "\n" || delimiter === "\r") throw new RangeError("delimiter");
  const lines = text.split(/\r?\n/);
  if (lines[lines.length - 1] === "") lines.pop();
  return lines.map((l) => {
    const out: string[] = [];
    let field = "";
    let quoted = false;
    for (let i = 0; i < l.length; i++) {
      const ch = l[i];
      if (quoted) {
        if (ch === '"' && l[i + 1] === '"') { field += '"'; i++; }
        else if (ch === '"') quoted = false;
        else field += ch;
      } else if (ch === '"' && field === "") quoted = true;
      else if (ch === delimiter) { out.push(field); field = ""; }
      else field += ch;
    }
    out.push(field);
    return out;
  });
}
