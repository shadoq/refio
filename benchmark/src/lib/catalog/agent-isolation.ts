// What an external agent is allowed to bring into a benchmark run. The agents track
// measures a coding agent as it ships; the plugins, skills and hooks of whoever runs
// the benchmark are not part of that. Left in place they change the result outright -
// a planning plugin makes the agent classify the task and stop instead of writing the
// file - and they differ from machine to machine, so the numbers stop comparing.
//
// Pure: it only decides what the settings file should say, the runner writes it.
export interface AgentWorkspaceSettings {
  enabledPlugins: Record<string, boolean>;
  // Left out when the run names no model, so the agent keeps its own default.
  model?: string;
}

// A settings file placed in the throwaway work dir switches the host's plugins off for
// this run only. Nothing in the user's own configuration is touched or read for auth.
// The model belongs here too: the host's own settings file can pin one, and that pin
// wins over the model named on the command line - the agent then refuses to start on a
// model the run never chose, or worse answers from the host's account while the row
// records the model that was asked for. Settings beside the work dir take precedence,
// so the run states its choice where it cannot be overruled.
export function agentWorkspaceSettings(
  hostEnabledPlugins: string[],
  model?: string,
): AgentWorkspaceSettings {
  return {
    enabledPlugins: Object.fromEntries(hostEnabledPlugins.map((name) => [name, false])),
    ...(model === undefined ? {} : { model }),
  };
}

// A package runner prepends every node_modules/.bin directory between the working
// directory and the filesystem root onto the search path. Any project in that chain
// holding an unrelated package named after a coding agent therefore shadows the real
// one, and the run measures that program instead: it takes the arguments, writes
// nothing and exits zero, which reads in the data as an agent that produced no work.
// So an external agent is launched with those entries removed and resolves the way the
// user's own shell would.
const INJECTED_BIN = /(^|\/)node_modules\/\.bin\/?$/;

export function agentSearchPath(path: string | undefined): string | undefined {
  if (!path) return undefined;
  const kept = path.split(":").filter((entry) => entry !== "" && !INJECTED_BIN.test(entry));
  // Everything was injected: a path that cannot launch the agent is worse than the
  // shadowing it guards against.
  return kept.length === 0 ? path : kept.join(":");
}

// A coding agent started from inside another one inherits the launcher's session: the
// variables below name a live session and its message channel, and a child that sees
// them attaches to it instead of standing alone. Observed on Windows: the agent
// reported the LAUNCHING session's model and refused to start, ignoring the model the
// run asked for - and had the endpoint not been overridden it would have answered from
// the launcher's account while the row recorded the local model. So the benchmark
// clears them, and a run started from a plain shell is unaffected because it carries
// none of them.
const HOST_SESSION_VARS = [
  "CLAUDECODE",
  "CLAUDE_CODE_CHILD_SESSION",
  "CLAUDE_CODE_ENTRYPOINT",
  "CLAUDE_CODE_EXECPATH",
  "CLAUDE_CODE_SESSION_ID",
  "CLAUDE_CODE_SESSION_ATTENDED",
  "CLAUDE_CODE_MESSAGING_SOCKET",
  "CLAUDE_CODE_MESSAGING_TOKEN",
  "CLAUDE_PID",
];

// Removals for the child's environment: a key mapped to undefined, which is how the
// runner spells "take this out". Only what the environment actually carries is named,
// so the overrides read as a record of what was cleared.
export function hostSessionOverrides(
  env: Record<string, string | undefined>,
): Record<string, undefined> {
  const cleared: Record<string, undefined> = {};
  for (const key of HOST_SESSION_VARS) {
    if (env[key] !== undefined) cleared[key] = undefined;
  }
  return cleared;
}
