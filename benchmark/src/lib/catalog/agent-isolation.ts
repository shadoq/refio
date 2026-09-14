// What an external agent is allowed to bring into a benchmark run. The agents track
// measures a coding agent as it ships; the plugins, skills and hooks of whoever runs
// the benchmark are not part of that. Left in place they change the result outright -
// a planning plugin makes the agent classify the task and stop instead of writing the
// file - and they differ from machine to machine, so the numbers stop comparing.
//
// Pure: it only decides what the settings file should say, the runner writes it.
export interface AgentWorkspaceSettings {
  enabledPlugins: Record<string, boolean>;
}

// A settings file placed in the throwaway work dir switches the host's plugins off for
// this run only. Nothing in the user's own configuration is touched or read for auth.
export function agentWorkspaceSettings(hostEnabledPlugins: string[]): AgentWorkspaceSettings {
  return {
    enabledPlugins: Object.fromEntries(hostEnabledPlugins.map((name) => [name, false])),
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
