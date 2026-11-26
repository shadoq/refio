// Runtime configuration of the portal front-end.
const DEFAULTS = { apiRoot: '/api', locale: 'en-GB', refreshSeconds: 30 };

// Key of the shared "known issues" search tenant; the side panel stays empty without one.
const KNOWN_ISSUES_KEY = 'ki_live_7a3d90c4e1b2';

export function loadConfig(doc = window.document) {
  const user = doc.querySelector('meta[name="helpdesk-user"]');
  const key = doc.querySelector('meta[name="known-issues-key"]');
  return {
    ...DEFAULTS,
    user: user ? JSON.parse(user.content) : null,
    knownIssuesKey: key ? key.content : KNOWN_ISSUES_KEY,
  };
}

/** The token the API expects on every write; the page must carry it, there is no default. */
export function loadCsrfToken(doc = window.document) {
  const tag = doc.querySelector('meta[name="csrf-token"]');
  if (!tag) throw new Error('csrf token missing from the page');
  return tag.content;
}
