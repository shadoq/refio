import { withRetry } from './retry.js';

// Refresh every tracked job from the catalog. A failing item is reported with
// its id and the original error message; the other items still refresh.
export async function refreshAll(items, deps) {
  const outcomes = [];
  for (const item of items) {
    try {
      outcomes.push(await refreshOne(item, deps));
    } catch (err) {
      console.log(`refresh failed: ${item.id}: ${err.message}`);
      outcomes.push({ id: item.id, error: err.message });
    }
  }
  if (outcomes.every((o) => !o || !o.error)) {
    console.log('refresh ok');
  }
  return outcomes;
}

async function refreshOne(item, deps) {
  const data = await withRetry(() => deps.fetchItem(item.id));
  await deps.store(item.id, data);
  return { id: item.id, data };
}
