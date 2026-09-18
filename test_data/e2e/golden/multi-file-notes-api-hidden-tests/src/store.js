// In-memory note storage. It owns the note shape, the ids and the version tags, so
// the routing layer never has to know how a note is stored.
const { randomUUID } = require("node:crypto");

const notes = new Map();

function etagOf(note) {
  return `"${note.id}-${Date.parse(note.updatedAt)}-${note.version}"`;
}

function create({ title, body, tags }) {
  const now = new Date().toISOString();
  const note = {
    id: randomUUID(),
    title,
    body,
    tags,
    createdAt: now,
    updatedAt: now,
    version: 1,
  };
  notes.set(note.id, note);
  return note;
}

function get(id) {
  return notes.get(id) ?? null;
}

// Two notes created in the same millisecond would otherwise sort arbitrarily, so the
// insertion order breaks the tie: inserted later means newer.
function list({ tag, page, limit }) {
  let all = [...notes.values()].reverse();
  if (tag) all = all.filter((n) => n.tags.includes(tag));
  all.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
  const start = (page - 1) * limit;
  return { items: all.slice(start, start + limit).map(publicNote), total: all.length };
}

function update(id, { title, body, tags }) {
  const note = notes.get(id);
  if (!note) return null;
  note.title = title;
  note.body = body;
  note.tags = tags;
  note.updatedAt = new Date().toISOString();
  note.version += 1;
  return note;
}

function remove(id) {
  return notes.delete(id);
}

// The stored note carries a version counter the API never exposes.
function publicNote(note) {
  return {
    id: note.id,
    title: note.title,
    body: note.body,
    tags: note.tags,
    createdAt: note.createdAt,
    updatedAt: note.updatedAt,
  };
}

module.exports = { create, get, list, update, remove, etagOf, publicNote };
