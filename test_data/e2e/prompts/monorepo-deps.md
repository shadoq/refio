Do not modify any files.

This monorepo has three workspace packages under `packages/`. Analyze the cross-package
dependency graph: state which package depends on which (read the package.json dependencies
and the require() calls).

Then answer: if the package at the bottom of the graph changes its public API, which
packages would be affected? Name that package and all its dependents.

This is read-only analysis. Do not change anything.
