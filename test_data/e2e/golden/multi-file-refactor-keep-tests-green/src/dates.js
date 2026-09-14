// Dates and money: the helpers every other module needs. They live here once so no
// module has to keep its own copy.

const MS_PER_DAY = 24 * 60 * 60 * 1000;

function parseDate(value) {
  if (value instanceof Date) return new Date(value.getTime());
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime())) throw new Error(`invalid date: ${value}`);
  return parsed;
}

function formatDate(date) {
  return parseDate(date).toISOString().slice(0, 10);
}

function daysBetween(from, to) {
  return Math.round((parseDate(to).getTime() - parseDate(from).getTime()) / MS_PER_DAY);
}

function addDays(date, days) {
  return formatDate(new Date(parseDate(date).getTime() + days * MS_PER_DAY));
}

function round2(value) {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

module.exports = { MS_PER_DAY, parseDate, formatDate, daysBetween, addDays, round2 };
