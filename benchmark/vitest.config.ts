import { defineConfig } from "vitest/config";
import { resolve } from "node:path";

export default defineConfig({
  resolve: {
    alias: {
      "@": resolve(__dirname, "src"),
      // The e2e toolchain lives in main and owns the case schema; the benchmark
      // extends it, so the dependency only ever points this way.
      "@e2e": resolve(__dirname, "..", "tools", "e2e", "src"),
      // tools/e2e has its own node_modules; pin zod to this app's copy so the
      // shared schemas do not drag in a second, type-incompatible instance.
      zod: resolve(__dirname, "node_modules", "zod"),
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/__tests__/setup.ts"],
  },
});
