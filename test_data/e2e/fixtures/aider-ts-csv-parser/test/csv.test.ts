import { test } from "node:test";
import assert from "node:assert/strict";
import { parseCsv, CsvError } from "../src/csv.ts";

test("plain records", () => {
  assert.deepEqual(parseCsv("a,b,c\n1,2,3"), [["a", "b", "c"], ["1", "2", "3"]]);
});

test("empty text has no records", () => {
  assert.deepEqual(parseCsv(""), []);
});

test("a final line break does not add a record", () => {
  assert.deepEqual(parseCsv("a,b\n"), [["a", "b"]]);
  assert.deepEqual(parseCsv("a,b\r\n"), [["a", "b"]]);
  assert.deepEqual(parseCsv("a,b\n\n"), [["a", "b"], [""]]);
});

test("CRLF, LF and a mix of both separate records", () => {
  assert.deepEqual(parseCsv("a,b\r\nc,d\r\n"), [["a", "b"], ["c", "d"]]);
  assert.deepEqual(parseCsv("a\r\nb\nc\r\nd"), [["a"], ["b"], ["c"], ["d"]]);
});

test("a lone CR is data", () => {
  assert.deepEqual(parseCsv("a\rb,c\n"), [["a\rb", "c"]]);
});

test("a blank line in the middle is a record with one empty field", () => {
  assert.deepEqual(parseCsv("a\n\nb"), [["a"], [""], ["b"]]);
  assert.deepEqual(parseCsv("\n"), [[""]]);
});

test("empty fields and a trailing delimiter", () => {
  assert.deepEqual(parseCsv(",,"), [["", "", ""]]);
  assert.deepEqual(parseCsv("a,\nb,"), [["a", ""], ["b", ""]]);
  assert.deepEqual(parseCsv("a,b,"), [["a", "b", ""]]);
});

test("fields are not trimmed", () => {
  assert.deepEqual(parseCsv(" a , b "), [[" a ", " b "]]);
});

test("quoted fields can hold the delimiter", () => {
  assert.deepEqual(parseCsv('"a,b",c'), [["a,b", "c"]]);
});

test("doubled quotes become one quote", () => {
  assert.deepEqual(parseCsv('"say ""hi""",x'), [['say "hi"', "x"]]);
  assert.deepEqual(parseCsv('""""'), [['"']]);
  assert.deepEqual(parseCsv('"a""",b'), [['a"', "b"]]);
});

test("an empty quoted field is an empty string", () => {
  assert.deepEqual(parseCsv('"",x'), [["", "x"]]);
  assert.deepEqual(parseCsv('x,""'), [["x", ""]]);
  assert.deepEqual(parseCsv('""'), [[""]]);
});

test("line breaks inside quotes are kept exactly", () => {
  assert.deepEqual(parseCsv('"a\nb",c\nd,e'), [["a\nb", "c"], ["d", "e"]]);
  assert.deepEqual(parseCsv('"a\r\nb",c\r\nd,e'), [["a\r\nb", "c"], ["d", "e"]]);
  assert.deepEqual(parseCsv('"\n"'), [["\n"]]);
});

test("a quote in the middle of an unquoted field is data", () => {
  assert.deepEqual(parseCsv('ab"c,d'), [['ab"c', "d"]]);
  assert.deepEqual(parseCsv(' "a",b'), [[' "a"', "b"]]);
});

test("custom delimiters", () => {
  assert.deepEqual(parseCsv("a;b,c;d", { delimiter: ";" }), [["a", "b,c", "d"]]);
  assert.deepEqual(parseCsv("a\tb\n1\t2", { delimiter: "\t" }), [["a", "b"], ["1", "2"]]);
  assert.deepEqual(parseCsv('"a;b";c', { delimiter: ";" }), [["a;b", "c"]]);
  assert.deepEqual(parseCsv('"a,b";c', { delimiter: ";" }), [["a,b", "c"]]);
  assert.deepEqual(parseCsv("a|", { delimiter: "|" }), [["a", ""]]);
});

test("invalid delimiters are rejected", () => {
  assert.throws(() => parseCsv("a", { delimiter: "" }), RangeError);
  assert.throws(() => parseCsv("a", { delimiter: ";;" }), RangeError);
  assert.throws(() => parseCsv("a", { delimiter: '"' }), RangeError);
  assert.throws(() => parseCsv("a", { delimiter: "\n" }), RangeError);
  assert.throws(() => parseCsv("a", { delimiter: "\r" }), RangeError);
});

function csvError(text: string, options?: { delimiter?: string }): CsvError {
  try {
    parseCsv(text, options);
  } catch (e) {
    assert.ok(e instanceof CsvError, "expected a CsvError");
    return e;
  }
  assert.fail("expected parseCsv to throw");
}

test("an unterminated quote is reported at the opening quote", () => {
  const e = csvError('a,b\n"c,d\ne');
  assert.equal(e.message, "Unterminated quote at line 2, column 1");
  assert.equal(e.line, 2);
  assert.equal(e.column, 1);
});

test("the opening quote may be in the middle of a line", () => {
  const e = csvError('x,"abc');
  assert.equal(e.message, "Unterminated quote at line 1, column 3");
  assert.equal(e.line, 1);
  assert.equal(e.column, 3);
});

test("lines inside earlier quoted fields and CRLF are counted", () => {
  const e = csvError('a\r\n"x\ny",b\r\nq,"open\nstill');
  assert.equal(e.message, "Unterminated quote at line 4, column 3");
  assert.equal(e.line, 4);
  assert.equal(e.column, 3);
});

test("a lone closing-quote pair at the end is still unterminated when odd", () => {
  const e = csvError('"abc""');
  assert.equal(e.message, "Unterminated quote at line 1, column 1");
});

test("a character after a closing quote is an error at that character", () => {
  const e = csvError('"a"b,c');
  assert.equal(e.message, "Unexpected character after closing quote at line 1, column 4");
  assert.equal(e.line, 1);
  assert.equal(e.column, 4);
});

test("position after a quoted field that spans lines", () => {
  const e = csvError('"a\nb"x');
  assert.equal(e.message, "Unexpected character after closing quote at line 2, column 3");
  assert.equal(e.line, 2);
  assert.equal(e.column, 3);
});

test("a closing quote may be followed by the delimiter, a line break or the end", () => {
  assert.deepEqual(parseCsv('"a",b\n"c"\r\n"d"'), [["a", "b"], ["c"], ["d"]]);
});

test("CsvError is an Error with a name", () => {
  const e = new CsvError("boom", 3, 4);
  assert.ok(e instanceof Error);
  assert.equal(e.name, "CsvError");
});
