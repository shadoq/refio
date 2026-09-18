// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  agentWorkspaceSettings,
  agentSearchPath,
  hostSessionOverrides,
} from "@/lib/catalog/agent-isolation";

// A benchmark must measure the agent as it ships, not the personal setup of whoever
// runs it. A plugin that answers "let me classify this task first" instead of writing
// the file turns a coding measurement into a measurement of somebody's config.
describe("agentWorkspaceSettings", () => {
  it("turns off every plugin the host has enabled", () => {
    const settings = agentWorkspaceSettings([
      "superpowers@claude-plugins-official",
      "playwright@claude-plugins-official",
    ]);
    expect(settings.enabledPlugins).toEqual({
      "superpowers@claude-plugins-official": false,
      "playwright@claude-plugins-official": false,
    });
  });

  it("is still a valid settings file when the host enables nothing", () => {
    expect(agentWorkspaceSettings([]).enabledPlugins).toEqual({});
  });

  // The host's own settings file can pin a model, and that pin beat the model the run
  // asked for on the command line: the agent refused to start, naming a model the run
  // never chose. The settings written beside the work dir take precedence, so the run
  // states its model there too.
  it("pins the model the run asked for, over whatever the host pinned", () => {
    expect(agentWorkspaceSettings([], "qwen3.8:27b-ctx64k").model).toBe("qwen3.8:27b-ctx64k");
  });

  // Letting the agent pick its own default is a real choice, and writing a key with no
  // value would take it away.
  it("pins no model when the run names none", () => {
    expect(agentWorkspaceSettings([])).not.toHaveProperty("model");
  });
});

// npx prepends every node_modules/.bin directory between the working directory and the
// filesystem root. A project tree that happens to hold an unrelated package named after
// a coding agent therefore SHADOWS the real one, and the benchmark measures that
// program instead - silently, because it accepts the arguments and exits zero.
describe("agentSearchPath", () => {
  it("drops the node_modules/.bin entries the package runner injected", () => {
    const path = agentSearchPath(
      [
        "/work/project/node_modules/.bin",
        "/work/node_modules/.bin",
        "/Users/x/.nvm/versions/node/v22/bin",
        "/usr/bin",
      ].join(":"),
    );
    expect(path).toBe("/Users/x/.nvm/versions/node/v22/bin:/usr/bin");
  });

  it("leaves a path that has none of them alone", () => {
    expect(agentSearchPath("/usr/local/bin:/usr/bin")).toBe("/usr/local/bin:/usr/bin");
  });

  // Stripping everything would leave the agent unlaunchable, which is worse than the
  // shadowing it protects against.
  it("keeps the path when it is nothing but injected entries", () => {
    const only = "/a/node_modules/.bin:/b/node_modules/.bin";
    expect(agentSearchPath(only)).toBe(only);
  });

  it("survives an empty or missing path", () => {
    expect(agentSearchPath(undefined)).toBeUndefined();
    expect(agentSearchPath("")).toBeUndefined();
  });
});

// Launching the benchmark FROM a coding agent leaves that agent's own session in the
// environment, and a child started there attaches to it: it answered with the host
// session's model instead of the one the run asked for, so the row would have carried
// a model that never ran. Anything naming the host session has to go.
describe("hostSessionOverrides", () => {
  it("clears the variables that attach a child to the host's session", () => {
    const cleared = hostSessionOverrides({
      CLAUDECODE: "1",
      CLAUDE_CODE_CHILD_SESSION: "1",
      CLAUDE_CODE_SESSION_ID: "b6eb5118",
      CLAUDE_CODE_MESSAGING_SOCKET: "\\.\pipe\LOCAL\cc-msg-1",
      CLAUDE_CODE_MESSAGING_TOKEN: "t",
      CLAUDE_CODE_ENTRYPOINT: "cli",
    });
    for (const key of [
      "CLAUDECODE",
      "CLAUDE_CODE_CHILD_SESSION",
      "CLAUDE_CODE_SESSION_ID",
      "CLAUDE_CODE_MESSAGING_SOCKET",
      "CLAUDE_CODE_MESSAGING_TOKEN",
      "CLAUDE_CODE_ENTRYPOINT",
    ]) {
      expect(cleared).toHaveProperty(key);
      expect(cleared[key]).toBeUndefined();
    }
  });

  // The endpoint and credential the run itself sets must survive, or the agent would be
  // sent back to its own cloud and the local model would never be measured.
  it("leaves the run's own routing and the user's shell alone", () => {
    const cleared = hostSessionOverrides({
      ANTHROPIC_BASE_URL: "http://127.0.0.1:11434",
      PATH: "/usr/bin",
      HOME: "/home/x",
    });
    expect(cleared).toEqual({});
  });

  it("names only what the environment actually carries", () => {
    expect(hostSessionOverrides({})).toEqual({});
  });
});
