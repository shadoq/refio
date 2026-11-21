export function buildOrder(deps: Record<string, string[]>): string[] {
  const graph = new Map<string, string[]>();
  const touch = (name: string) => {
    if (!graph.has(name)) graph.set(name, []);
  };
  for (const name of Object.keys(deps)) {
    touch(name);
    const unique = [...new Set(deps[name])].sort();
    graph.set(name, unique);
    for (const d of unique) touch(d);
  }

  const remaining = new Map<string, number>();
  const dependents = new Map<string, string[]>();
  for (const [name, list] of graph) {
    remaining.set(name, list.length);
    for (const d of list) {
      if (!dependents.has(d)) dependents.set(d, []);
      dependents.get(d)!.push(name);
    }
  }

  const ready = [...graph.keys()].filter((n) => remaining.get(n) === 0).sort();
  const order: string[] = [];
  while (ready.length > 0) {
    const next = ready.shift()!;
    order.push(next);
    for (const user of dependents.get(next) ?? []) {
      const left = remaining.get(user)! - 1;
      remaining.set(user, left);
      if (left === 0) {
        ready.push(user);
        ready.sort();
      }
    }
  }

  if (order.length === graph.size) return order;
  const stuck = new Set([...graph.keys()].filter((n) => !order.includes(n)));
  throw new Error("Dependency cycle: " + findCycle(graph, stuck).join(" -> "));
}

function reaches(graph: Map<string, string[]>, from: string, target: string): boolean {
  const seen = new Set<string>();
  const stack = [...(graph.get(from) ?? [])];
  while (stack.length > 0) {
    const n = stack.pop()!;
    if (n === target) return true;
    if (seen.has(n)) continue;
    seen.add(n);
    stack.push(...(graph.get(n) ?? []));
  }
  return false;
}

function findCycle(graph: Map<string, string[]>, stuck: Set<string>): string[] {
  const start = [...stuck].sort().reverse().find((n) => reaches(graph, n, n))!;
  // Breadth-first search with sorted neighbours: the first path that closes on
  // the start is the shortest and, among equals, the smallest module by module.
  const parent = new Map<string, string>();
  const queue = [start];
  const seen = new Set<string>([start]);
  while (queue.length > 0) {
    const node = queue.shift()!;
    for (const next of graph.get(node) ?? []) {
      if (next === start) {
        const path = [node];
        while (path[0] !== start) path.unshift(parent.get(path[0])!);
        return [...path, start];
      }
      if (!seen.has(next)) {
        seen.add(next);
        parent.set(next, node);
        queue.push(next);
      }
    }
  }
  throw new Error("unreachable");
}
