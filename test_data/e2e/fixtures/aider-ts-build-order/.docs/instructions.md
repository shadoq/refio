# Instructions

Implement `buildOrder(deps)` in `src/build-order.ts`. It decides in which order the modules of a workspace must be built.

## Input

`deps` is a plain object that maps a module name to the array of module names it depends on:

```ts
{ app: ["lib", "util"], lib: ["util"], util: [] }
```

A module that is only mentioned as a dependency (it has no key of its own) is a module with no dependencies and is part of the result. Repeated names in one dependency array count once.

## Order

The result lists every module exactly once, and a module always comes after everything it depends on. Among the modules that are ready to be built at a given moment (all their dependencies are already in the result) the alphabetically smallest comes first, using plain `<` string comparison. This rule is applied at every step, so it is not the same as a depth-first walk in alphabetical order.

## Cycles

When the dependencies contain a cycle, `buildOrder` throws an `Error` whose message is exactly

```text
Dependency cycle: a -> b -> c -> a
```

The reported cycle is chosen like this:

1. Take the alphabetically smallest module that lies on any cycle. The path starts and ends with it.
2. Among all cycles through that module, report the shortest one.
3. If several shortest cycles exist, report the one that is smaller when compared module by module from the start.

A module that depends on itself is a cycle of length one: `Dependency cycle: a -> a`.

Modules that only depend on a cycle, or that a cycle depends on, are not part of it and never appear in the message.

The input object is never modified.
