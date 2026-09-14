// @vitest-environment node
import { describe, it, expect } from "vitest";
import { agentWorkspaceSettings, agentSearchPath } from "@/lib/catalog/agent-isolation";

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
