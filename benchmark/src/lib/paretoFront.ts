export interface ParetoPoint {
  id: string;
  x: number; // cost or duration (lower is better)
  y: number; // score (higher is better)
}

export function paretoFront(points: ParetoPoint[]): Set<string> {
  const front = new Set<string>();
  for (const p of points) {
    // p is dominated if some other point q has q.x <= p.x AND q.y >= p.y
    // with at least one strict inequality
    const dominated = points.some(
      (q) => q.id !== p.id && q.x <= p.x && q.y >= p.y && (q.x < p.x || q.y > p.y),
    );
    if (!dominated) front.add(p.id);
  }
  return front;
}
