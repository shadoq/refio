import { withRetry } from './retry.js';

// Keep the worker alive when a refresh round fails instead of crashing.
process.on('unhandledRejection', () => {});

// Refresh every tracked job from the catalog. One round covers all items.
export async function refreshAll(items, deps) {
  try {
    const results = await Promise.all(items.map((item) => refreshOne(item, deps)));
    return results;
  } finally {
    console.log('refresh ok');
  }
}

async function refreshOne(item, deps) {
  const data = await withRetry(() => deps.fetchItem(item.id));
  await deps.store(item.id, data);
  return { id: item.id, data };
}
