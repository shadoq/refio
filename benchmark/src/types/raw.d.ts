// Vite serves a `?raw` import as the file's text. The trace tests read recorded agent
// output that way, so the sample stays a real file rather than a string literal.
declare module "*?raw" {
  const content: string;
  export default content;
}
