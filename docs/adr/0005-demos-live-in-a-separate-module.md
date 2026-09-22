---
status: accepted
date: 2026-09-21
---

# Demos live in a separate module

Spec section 0 allows a second child module only when a dependency forces it. Demo programs
need JGit to read git history, and they need a runnable main class. Neither belongs in
`kleene`. Demo code must not change the runtime dependencies of `kleene`.

Decision: add a module `demo` (artifactId `kleene-demo`). It is never published. It holds one
package per demo, under `kleene.demo.<name>`. It may add its own dependencies. `kleene` never
depends on `demo`. Root `mvn test` runs the demo tests, and they use no network.

## Considered options

- Put demos in `kleene`'s test sources (rejected: puts JGit on the library's test classpath,
  and gives no runnable main class).
- A separate repository (rejected: drifts from the API over time).

## Consequences

Spec section 0 and `AGENTS.md` name both modules. `docs/adr/README.md` lists this decision.
