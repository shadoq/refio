# orders report client

`src/client.ts` fetches the weekly orders report from the orders service and
prints it to the console; `src/models.ts` holds the label helpers used by
the report. There is no bundler - the script runs under Node 18+ (global
`fetch`) after a plain `tsc` transpile.
