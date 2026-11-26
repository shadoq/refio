import { loadCsrfToken } from './config.js';

function parse(response) {
  if (!response.ok) return Promise.reject(new Error(`request failed with ${response.status}`));
  return response.json();
}

/** JSON request against the portal API; carries the CSRF token the API expects on every write. */
export function request(path, { method = 'GET', body } = {}) {
  return fetch(path, {
    method,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': loadCsrfToken() },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then(parse);
}

/** Form post for the legacy export endpoints, which still take urlencoded bodies. */
export function postForm(path, fields) {
  return fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
  }).then(parse);
}

export function searchTickets(query) {
  return request(`/api/tickets?q=${encodeURIComponent(query)}`);
}

/** Link to a stored attachment. Since 2.1.0 only plain file names are linked. */
export function attachmentUrl(name) {
  if (typeof name !== 'string' || name.includes('..') || /[/\\]/.test(name)) return null;
  return `/download?name=${encodeURIComponent(name)}`;
}
