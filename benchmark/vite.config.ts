import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve, dirname, join, basename } from "node:path";
import { promoteInboxEntry, discardInboxEntry } from "./src/lib/catalog/inbox";
import type { Score } from "./src/schema/results";

const ALLOWED_FILES = {
  results: "data/results.json",
  tasks: "data/tasks.json",
} as const;

const POLISH_CHARS: Record<string, string> = {
  ą: "a",
  ć: "c",
  ę: "e",
  ł: "l",
  ń: "n",
  ó: "o",
  ś: "s",
  ź: "z",
  ż: "z",
  Ą: "A",
  Ć: "C",
  Ę: "E",
  Ł: "L",
  Ń: "N",
  Ó: "O",
  Ś: "S",
  Ź: "Z",
  Ż: "Z",
};

const WINDOWS_RESERVED_NAMES = new Set([
  "con",
  "prn",
  "aux",
  "nul",
  "com1",
  "com2",
  "com3",
  "com4",
  "com5",
  "com6",
  "com7",
  "com8",
  "com9",
  "lpt1",
  "lpt2",
  "lpt3",
  "lpt4",
  "lpt5",
  "lpt6",
  "lpt7",
  "lpt8",
  "lpt9",
]);

function asciiFold(value: string): string {
  return value
    .replace(/[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]/g, (char) => POLISH_CHARS[char] ?? char)
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "");
}

function sanitizePathSegment(value: string, fallback: string): string {
  const sanitized = asciiFold(value)
    .toLowerCase()
    .replace(/[/\\]+/g, "-")
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .replace(/\.{2,}/g, ".")
    .replace(/[. ]+$/g, "");

  const safe = sanitized || fallback;
  return WINDOWS_RESERVED_NAMES.has(safe) ? `${safe}-file` : safe;
}

function getFileExtension(filename: string): string {
  const nameOnly = basename(filename.replace(/\\/g, "/"));
  const dotIndex = nameOnly.lastIndexOf(".");
  if (dotIndex <= 0 || dotIndex === nameOnly.length - 1) return "";
  return sanitizePathSegment(nameOnly.slice(dotIndex + 1), "");
}

function buildAttachmentFilename(input: {
  originalFilename: string;
  modelProvider?: string;
  modelName?: string;
  modelId?: string;
  attemptNumber?: string;
  fileNumber?: string;
}): string {
  const provider = sanitizePathSegment(input.modelProvider ?? "", "provider");
  const rawModel =
    input.modelName?.trim() ||
    input.modelId?.split(/[/:]/).filter(Boolean).at(-1) ||
    "model";
  const model = sanitizePathSegment(rawModel, "model");
  const attempt = Math.max(1, Number.parseInt(input.attemptNumber ?? "1", 10) || 1);
  const fileNumber = Math.max(1, Number.parseInt(input.fileNumber ?? "1", 10) || 1);
  const ext = getFileExtension(input.originalFilename);
  const base = `${provider}-${model}-attempt-${attempt}-${fileNumber}`;

  return ext ? `${base}.${ext}` : base;
}

async function nextAvailableFilename(dir: string, filename: string): Promise<string> {
  const dotIndex = filename.lastIndexOf(".");
  const base = dotIndex > 0 ? filename.slice(0, dotIndex) : filename;
  const ext = dotIndex > 0 ? filename.slice(dotIndex) : "";
  let candidate = filename;
  let suffix = 2;

  while (true) {
    try {
      await access(join(dir, candidate));
      candidate = `${base}-${suffix}${ext}`;
      suffix += 1;
    } catch {
      return candidate;
    }
  }
}

function saveDataPlugin(): Plugin {
  return {
    name: "save-data",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/__save", async (req, res) => {
        if (req.method !== "POST") {
          res.statusCode = 405;
          res.end("Method Not Allowed");
          return;
        }
        try {
          let raw = "";
          for await (const chunk of req) raw += chunk;
          const body = JSON.parse(raw) as { file: keyof typeof ALLOWED_FILES; data: unknown };

          const target = ALLOWED_FILES[body.file];
          if (!target) {
            res.statusCode = 400;
            res.setHeader("content-type", "application/json");
            res.end(JSON.stringify({ error: "Unknown file key" }));
            return;
          }

          const filePath = resolve(process.cwd(), target);
          await mkdir(dirname(filePath), { recursive: true });
          await writeFile(filePath, JSON.stringify(body.data, null, 2) + "\n", "utf8");

          res.statusCode = 200;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify({ ok: true, path: target }));
        } catch (err) {
          res.statusCode = 500;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify({ error: String(err) }));
        }
      });
    },
  };
}

function uploadPlugin(): Plugin {
  return {
    name: "upload-attachment",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/__upload", async (req, res) => {
        if (req.method !== "POST") {
          res.statusCode = 405;
          res.end("Method Not Allowed");
          return;
        }
        try {
          // Read raw body as buffer
          const chunks: Buffer[] = [];
          for await (const chunk of req) chunks.push(Buffer.from(chunk));
          const body = Buffer.concat(chunks);

          // Parse multipart boundary from Content-Type header
          const contentType = (req.headers["content-type"] ?? "") as string;
          const boundaryMatch = contentType.match(/boundary=(.+)$/);
          if (!boundaryMatch) {
            res.statusCode = 400;
            res.setHeader("content-type", "application/json");
            res.end(JSON.stringify({ error: "Missing boundary in Content-Type" }));
            return;
          }
          const boundary = "--" + boundaryMatch[1];
          const boundaryBuf = Buffer.from(boundary);

          // Split on boundary
          const parts: Buffer[] = [];
          let start = 0;
          while (start < body.length) {
            const idx = body.indexOf(boundaryBuf, start);
            if (idx === -1) break;
            if (start !== 0) {
              // Part content between previous boundary and this one
              const part = body.slice(start, idx - 2); // strip \r\n before boundary
              parts.push(part);
            }
            start = idx + boundaryBuf.length + 2; // skip \r\n after boundary
          }

          // Find the file part (has Content-Disposition with filename)
          let resultId = "";
          let filename = "";
          let originalFilename = "";
          const uploadMetadata: {
            modelProvider?: string;
            modelName?: string;
            modelId?: string;
            attemptNumber?: string;
            fileNumber?: string;
          } = {};
          let fileContent: Buffer | null = null;

          for (const part of parts) {
            const headerEnd = part.indexOf(Buffer.from("\r\n\r\n"));
            if (headerEnd === -1) continue;
            const headerStr = part.slice(0, headerEnd).toString("utf8");
            const content = part.slice(headerEnd + 4);

            const dispositionMatch = headerStr.match(
              /Content-Disposition:.*?name="([^"]+)"(?:.*?filename="([^"]+)")?/i,
            );
            if (!dispositionMatch) continue;

            const fieldName = dispositionMatch[1];
            const fname = dispositionMatch[2];

            if (fieldName === "resultId") {
              resultId = content.toString("utf8").trim();
            } else if (fieldName === "modelProvider") {
              uploadMetadata.modelProvider = content.toString("utf8").trim();
            } else if (fieldName === "modelName") {
              uploadMetadata.modelName = content.toString("utf8").trim();
            } else if (fieldName === "modelId") {
              uploadMetadata.modelId = content.toString("utf8").trim();
            } else if (fieldName === "attemptNumber") {
              uploadMetadata.attemptNumber = content.toString("utf8").trim();
            } else if (fieldName === "fileNumber") {
              uploadMetadata.fileNumber = content.toString("utf8").trim();
            } else if (fieldName === "file" && fname) {
              originalFilename = fname;
              filename = buildAttachmentFilename({
                originalFilename,
                ...uploadMetadata,
              });
              fileContent = content;
            }
          }

          if (!resultId || !filename || !fileContent) {
            res.statusCode = 400;
            res.setHeader("content-type", "application/json");
            res.end(JSON.stringify({ error: "Missing resultId, filename, or file content" }));
            return;
          }

          const safeResultId = sanitizePathSegment(resultId, "result");
          const dir = resolve(process.cwd(), "data/attachments", safeResultId);
          await mkdir(dir, { recursive: true });
          const safeFilename = await nextAvailableFilename(dir, filename);
          const destPath = join(dir, safeFilename);
          await writeFile(destPath, fileContent);

          const relativePath = `attachments/${safeResultId}/${safeFilename}`;
          res.statusCode = 200;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify({ ok: true, path: relativePath }));
        } catch (err) {
          res.statusCode = 500;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify({ error: String(err) }));
        }
      });
    },
  };
}

// Apply an inbox promote/discard to the CURRENT results.json server-side, instead of
// letting the client POST a whole-file snapshot built from its (possibly stale) cache.
// That snapshot clobbers entries another writer (e.g. the import-runs catalog importer)
// appended after the client loaded - the observed lost-update. Read-mutate-write here
// shrinks the race window to this handler and reuses the same tested pure helpers.
function mutateResultsPlugin(): Plugin {
  return {
    name: "mutate-results",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/__mutate-results", async (req, res) => {
        if (req.method !== "POST") {
          res.statusCode = 405;
          res.end("Method Not Allowed");
          return;
        }
        try {
          let raw = "";
          for await (const chunk of req) raw += chunk;
          const body = JSON.parse(raw) as {
            op?: "promote" | "discard";
            entryId?: string;
            scores?: unknown;
          };
          if (!body.entryId || (body.op !== "promote" && body.op !== "discard")) {
            res.statusCode = 400;
            res.setHeader("content-type", "application/json");
            res.end(JSON.stringify({ error: "expected { op: 'promote'|'discard', entryId }" }));
            return;
          }

          const filePath = resolve(process.cwd(), ALLOWED_FILES.results);
          const current = JSON.parse(await readFile(filePath, "utf8"));
          const now = new Date().toISOString();
          const next =
            body.op === "promote"
              ? promoteInboxEntry(current, body.entryId, (body.scores ?? []) as Score[], now)
              : discardInboxEntry(current, body.entryId);

          await mkdir(dirname(filePath), { recursive: true });
          await writeFile(filePath, JSON.stringify(next, null, 2) + "\n", "utf8");

          res.statusCode = 200;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify({ ok: true, data: next }));
        } catch (err) {
          res.statusCode = 500;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify({ error: String(err) }));
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), saveDataPlugin(), uploadPlugin(), mutateResultsPlugin()],
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
  // The dev server must be allowed to read the shared e2e schema outside benchmark/.
  server: {
    fs: { allow: [resolve(__dirname), resolve(__dirname, "..", "tools", "e2e")] },
  },
  // Absolute path of the data dir on this dev machine, so the review UI can build
  // idea:// links that open an artifact file in IntelliJ. Dev-only (the admin pages
  // that use it are gated behind import.meta.env.DEV).
  define: {
    "import.meta.env.VITE_DATA_ROOT": JSON.stringify(resolve(__dirname, "data")),
  },
});
