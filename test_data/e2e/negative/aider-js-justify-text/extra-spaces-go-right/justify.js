'use strict';

function spread(words, width) {
  const letters = words.reduce((sum, w) => sum + w.length, 0);
  const gaps = words.length - 1;
  const spaces = width - letters;
  const base = Math.floor(spaces / gaps);
  const extra = spaces % gaps;
  let line = '';
  words.forEach((word, i) => {
    line += word;
    if (i < gaps) line += ' '.repeat(base + (i >= gaps - extra ? 1 : 0));
  });
  return line;
}

function justify(text, width) {
  if (!Number.isInteger(width) || width < 2) throw new RangeError('width must be an integer of at least 2');

  const rows = [];
  let current = [];
  let currentLength = 0;
  const close = () => {
    if (current.length > 0) rows.push(current);
    current = [];
    currentLength = 0;
  };

  for (let word of text.split(/\s+/).filter(Boolean)) {
    if (word.length > width) {
      close();
      while (word.length > width) {
        rows.push([`${word.slice(0, width - 1)}-`]);
        word = word.slice(width - 1);
      }
    }
    if (current.length > 0 && currentLength + 1 + word.length > width) close();
    currentLength += (current.length > 0 ? 1 : 0) + word.length;
    current.push(word);
  }
  close();

  return rows.map((words, i) =>
    i === rows.length - 1 || words.length === 1 ? words.join(' ') : spread(words, width)
  );
}

module.exports = { justify };
