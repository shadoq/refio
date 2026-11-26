/** Text helpers shared by the ticket list, the ticket page and the queue panel. */

const DAY = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

/** `4 Nov 2026`; a date the API cannot parse shows as "unknown" rather than "Invalid Date". */
export function formatDate(value) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? 'unknown' : DAY.format(date);
}

/** `JD` for `Jane Doe`; `?` when no name is known. */
export function initials(name) {
  const parts = String(name ?? '').trim().split(/\s+/).filter(Boolean);
  return parts.slice(0, 2).map((part) => part.charAt(0).toUpperCase()).join('') || '?';
}
