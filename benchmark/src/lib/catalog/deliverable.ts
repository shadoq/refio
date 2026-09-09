// Finding the artifact a benchmark task produced. A task in data/tasks.json names its
// output file inside the prompt text ("Use file name \"snake_{{MODEL_ID}}_01.html\"")
// rather than in a field, so after a run the artifact is identified by what actually
// appeared in the work dir. Pure, no IO: the caller lists the files.

// Written into the work dir by the runner or the agent itself, never a deliverable.
export const SCAFFOLDING_FILES = ["prompt.md", "run.json", "agent-last-message.txt"];

// The single html artifact the run produced, or null when there is nothing to score.
// More than one html file is also null: the task asks for one, and picking a winner
// would hide that the run did something else.
export function pickDeliverable(producedFiles: string[]): string | null {
  const candidates = producedFiles.filter(
    (f) => !SCAFFOLDING_FILES.includes(f) && f.toLowerCase().endsWith(".html"),
  );
  return candidates.length === 1 ? candidates[0] : null;
}
