#!/usr/bin/env node
"use strict";

// Command-line entry point. The report contract is described in README.md.
const { writeReport } = require("../src/summarize");

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--input") args.input = argv[++i];
    else if (argv[i] === "--output") args.output = argv[++i];
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
if (!args.input || !args.output) {
  console.error("usage: node bin/report.js --input <file> --output <file>");
  process.exit(2);
}
process.exitCode = writeReport(args.input, args.output);
