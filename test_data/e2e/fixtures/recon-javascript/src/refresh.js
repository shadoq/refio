/**
 * Calls `refresh` every `seconds` while the document is visible; a hidden tab is left alone
 * and picks up changes the next time it is shown. Returns a function that stops the refresh.
 */
export function startAutoRefresh(doc, refresh, seconds) {
  const view = doc.defaultView;
  const timer = view.setInterval(() => {
    if (doc.visibilityState === 'hidden') return;
    refresh();
  }, seconds * 1000);
  return () => view.clearInterval(timer);
}
