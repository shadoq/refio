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
  if (delimiter.length !== 1 || delimiter === '"' || delimiter === "\n" || delimiter === "\r") {
    throw new RangeError(`invalid delimiter: ${JSON.stringify(delimiter)}`);
  }

  const n = text.length;
  const records: string[][] = [];
  let record: string[] = [];
  let i = 0;
  let line = 1;
  let lineStart = 0;

  const atLineBreak = (at: number) => text[at] === "\n" || (text[at] === "\r" && text[at + 1] === "\n");

  while (i < n) {
    if (text[i] === '"') {
      const quoteLine = line;
      const quoteColumn = i - lineStart + 1;
      i++;
      let field = "";
      for (;;) {
        if (i >= n) {
          throw new CsvError(`Unterminated quote at line ${line}, column ${i - lineStart + 1}`, line, i - lineStart + 1);
        }
        const ch = text[i];
        if (ch === '"') {
          if (text[i + 1] === '"') {
            field += '"';
            i += 2;
            continue;
          }
          i++;
          break;
        }
        if (ch === "\n") {
          line++;
          lineStart = i + 1;
        }
        field += ch;
        i++;
      }
      if (i < n && text[i] !== delimiter && !atLineBreak(i)) {
        const column = i - lineStart + 1;
        throw new CsvError(`Unexpected character after closing quote at line ${line}, column ${column}`, line, column);
      }
      record.push(field);
    } else {
      const start = i;
      while (i < n && text[i] !== delimiter && !atLineBreak(i)) i++;
      record.push(text.slice(start, i));
    }

    if (i >= n) {
      records.push(record);
      record = [];
      break;
    }
    if (text[i] === delimiter) {
      i++;
      if (i >= n) {
        record.push("");
        records.push(record);
        record = [];
      }
      continue;
    }
    i += text[i] === "\r" ? 2 : 1;
    line++;
    lineStart = i;
    records.push(record);
    record = [];
  }
  return records;
}
