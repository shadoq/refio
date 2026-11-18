export function buildOrder(deps: Record<string, string[]>): string[] {
  const names = new Set<string>();
  for (const [k, v] of Object.entries(deps)) {
    names.add(k);
    v.forEach((d) => names.add(d));
  }
  const done = new Set<string>();
  const active: string[] = [];
  const order: string[] = [];
  const visit = (n: string) => {
    if (done.has(n)) return;
    const at = active.indexOf(n);
    if (at >= 0) {
      const cycle = [...active.slice(at), n];
      throw new Error("Dependency cycle: " + cycle.join(" -> "));
    }
    active.push(n);
    for (const d of [...new Set(deps[n] ?? [])].sort()) visit(d);
    active.pop();
    done.add(n);
    order.push(n);
  };
  for (const n of [...names].sort()) visit(n);
  return order;
}
