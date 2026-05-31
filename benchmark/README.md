# benchmark.refio

Static benchmark viewer for evaluating which local and cloud models are a good fit for Refio.

The benchmark is intentionally focused on simple, repeatable coding tasks. It is not trying to prove that small local models can solve complex software projects. Its purpose is to map practical model behavior for Refio's likely target use cases: lightweight coding tasks, first-shot usefulness, visible tool use, reliability, speed, API cost and local viability.

## What It Measures

- Quality scores per task and criterion
- First-shot usability
- Reliability across repeated attempts
- Local viability against cloud baselines
- Runtime and estimated token throughput
- API cost for cloud models
- Per-task behavior and model-to-model comparisons

## What It Is For

- Choosing sensible default models for Refio modes
- Understanding where local models are good enough
- Comparing small, medium and cloud models on the same task set
- Keeping benchmark artifacts, screenshots and notes linkable
- Avoiding model choices based only on intuition

## What It Is Not

- A fully automated benchmark runner
- A general LLM leaderboard
- A claim that local models should handle large, complex agent tasks
- A replacement for manual review of generated artifacts

## Development

```bash
npm install
npm run dev
npm run build
```

Data lives in `data/tasks.json` and `data/results.json`. In development mode, the admin pages can edit those files through the Vite dev server helpers.
