import { test } from "node:test";
import assert from "node:assert/strict";
import { TextBuffer } from "../src/text-buffer.ts";

test("starts with initial text and cursor at 0", () => {
  const b = new TextBuffer("abc");
  assert.equal(b.text, "abc");
  assert.equal(b.cursor, 0);
  assert.equal(new TextBuffer().text, "");
});

test("insert moves the cursor after the inserted text", () => {
  const b = new TextBuffer("ab");
  b.insert(1, "XY");
  assert.equal(b.text, "aXYb");
  assert.equal(b.cursor, 3);
});

test("delete leaves the cursor at the position", () => {
  const b = new TextBuffer("hello world");
  b.delete(5, 6);
  assert.equal(b.text, "hello");
  assert.equal(b.cursor, 5);
});

test("out of range edits throw RangeError and change nothing", () => {
  const b = new TextBuffer("abc");
  assert.throws(() => b.insert(4, "x"), RangeError);
  assert.throws(() => b.insert(-1, "x"), RangeError);
  assert.throws(() => b.delete(2, 2), RangeError);
  assert.throws(() => b.delete(-1, 1), RangeError);
  assert.throws(() => b.delete(1, -1), RangeError);
  assert.throws(() => b.setCursor(4), RangeError);
  assert.throws(() => b.setCursor(1.5), RangeError);
  assert.equal(b.text, "abc");
  assert.equal(b.cursor, 0);
  assert.equal(b.undo(), false);
});

test("undo restores text and the cursor from before the edit", () => {
  const b = new TextBuffer("hello");
  b.setCursor(5);
  b.insert(5, " world");
  b.setCursor(0);
  assert.equal(b.undo(), true);
  assert.equal(b.text, "hello");
  assert.equal(b.cursor, 5);
});

test("redo restores text and the cursor from after the edit", () => {
  const b = new TextBuffer("hello");
  b.setCursor(5);
  b.insert(5, " world");
  b.setCursor(2);
  b.undo();
  b.setCursor(0);
  assert.equal(b.redo(), true);
  assert.equal(b.text, "hello world");
  assert.equal(b.cursor, 11);
});

test("undo of a delete brings the removed text back", () => {
  const b = new TextBuffer("abcdef");
  b.setCursor(4);
  b.delete(1, 3);
  assert.equal(b.text, "aef");
  b.undo();
  assert.equal(b.text, "abcdef");
  assert.equal(b.cursor, 4);
});

test("undo and redo with empty history return false", () => {
  const b = new TextBuffer("x");
  assert.equal(b.undo(), false);
  assert.equal(b.redo(), false);
});

test("several steps undo in reverse order and redo in order", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.insert(1, "b");
  b.insert(2, "c");
  b.undo();
  assert.equal(b.text, "ab");
  b.undo();
  assert.equal(b.text, "a");
  b.redo();
  b.redo();
  assert.equal(b.text, "abc");
  assert.equal(b.redo(), false);
});

test("a new edit discards the redo history", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.insert(1, "b");
  b.undo();
  b.insert(1, "z");
  assert.equal(b.redo(), false);
  assert.equal(b.text, "az");
});

test("no-op edits are not history and do not clear redo", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.undo();
  b.insert(0, "");
  b.delete(0, 0);
  assert.equal(b.redo(), true);
  assert.equal(b.text, "a");
  assert.equal(b.undo(), true);
  assert.equal(b.undo(), false);
});

test("rejected edits do not clear redo", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.undo();
  assert.throws(() => b.insert(5, "x"), RangeError);
  assert.equal(b.redo(), true);
});

test("a group is a single undo step", () => {
  const b = new TextBuffer("abc");
  b.setCursor(1);
  b.beginGroup();
  b.insert(3, "def");
  b.insert(6, "!");
  b.delete(0, 1);
  b.endGroup();
  assert.equal(b.text, "bcdef!");
  b.undo();
  assert.equal(b.text, "abc");
  assert.equal(b.cursor, 1);
  assert.equal(b.undo(), false);
  b.redo();
  assert.equal(b.text, "bcdef!");
  assert.equal(b.cursor, 0);
});

test("nested groups collapse into the outermost one", () => {
  const b = new TextBuffer();
  b.beginGroup();
  b.insert(0, "a");
  b.beginGroup();
  b.insert(1, "b");
  b.endGroup();
  b.insert(2, "c");
  b.endGroup();
  assert.equal(b.text, "abc");
  b.undo();
  assert.equal(b.text, "");
  assert.equal(b.undo(), false);
});

test("empty and no-op-only groups leave no history", () => {
  const b = new TextBuffer("a");
  b.beginGroup();
  b.endGroup();
  b.beginGroup();
  b.insert(0, "");
  b.endGroup();
  assert.equal(b.undo(), false);
});

test("an empty group does not clear redo", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.undo();
  b.beginGroup();
  b.endGroup();
  assert.equal(b.redo(), true);
});

test("the first edit inside a group clears redo", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.undo();
  b.beginGroup();
  b.insert(0, "z");
  assert.equal(b.text, "z");
  b.endGroup();
  assert.equal(b.redo(), false);
});

test("endGroup without beginGroup throws", () => {
  const b = new TextBuffer();
  assert.throws(() => b.endGroup(), Error);
  b.beginGroup();
  b.endGroup();
  assert.throws(() => b.endGroup(), Error);
});

test("undo and redo throw while a group is open", () => {
  const b = new TextBuffer();
  b.insert(0, "a");
  b.beginGroup();
  assert.throws(() => b.undo(), Error);
  assert.throws(() => b.redo(), Error);
  b.endGroup();
  assert.equal(b.undo(), true);
});

test("group steps interleave with plain steps", () => {
  const b = new TextBuffer();
  b.insert(0, "x");
  b.beginGroup();
  b.insert(1, "y");
  b.insert(2, "z");
  b.endGroup();
  b.insert(3, "!");
  b.undo();
  assert.equal(b.text, "xyz");
  b.undo();
  assert.equal(b.text, "x");
  assert.equal(b.cursor, 1);
});
