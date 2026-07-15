# ADR: Port Algebra Consolidation

- **Status:** Accepted
- **Date:** 2026-07-14
- **Context:** `docs/port-algebra.md`

## Decision

Tark will keep its domain-specific port facades as the public API while using
small reusable algebra shapes for law testing, documentation, and carefully
scoped derivations.

The current typeclasses do not collapse cleanly into standard Cats typeclasses
such as `Functor`, `Applicative`, or `Monad`. Some are pure transformations or
state lenses; others are effectful service contracts with resource and adapter
semantics. Consolidation is useful only where the shared algebra preserves
domain intent and improves testability.

## Consolidation Decisions

| Area | Decision | Rationale |
| --- | --- | --- |
| State access | Use `Updatable[A, B]` and `Appendable[A, B]` as reusable shapes, but keep `ContextOps`, `ConfigOps`, and `ReActStateOps` as public facades. | The shared laws are useful, but the domain names communicate memory, agent state, config, and ReAct semantics better than generic lenses. |
| Registry | Treat `Registry[C, K, V]` as the preferred shared shape for map-like registration. Keep `ToolRegistry[C]` for compatibility. | `ToolRegistry` maps cleanly to lookup/register/last-write-wins laws, so it is the strongest consolidation candidate. |
| Command dispatch | Keep `SlashCommand`, `SlashCommandRouter`, `KeyboardHandler`, and `InputProcessor` domain-specific. Use `Dispatcher` only for the reusable first-match-plus-fallback pattern. | Slash commands, keyboard shortcuts, and ReAct dispatch have different state and effect boundaries even though they share routing laws. |
| Rendering | Keep `Layout`, `CellRenderer`, `Glyph`, `Formattable`, and `ScreenWriter` separate. Use `ScreenWriterF[F, A]` as a non-breaking effect-generic prototype. | Rendering has several distinct contracts: measurement, formatting, screen construction, cell encoding, and output effects. Combining them would hide useful boundaries. |
| LLM pipelines | Keep coarse services such as `LlmClient[F]` and `ReActLlmClient[F]`, but derive them from smaller protocol algebras where possible. | `ToMessages`, `LlmRequestCreator`, `LlmExecutor`, and `LlmResponseParser` compose into testable provider-independent behavior. Provider semantics remain adapter-owned. |

## Safe Higher-Order Derivations

The following behavior can be defined from smaller algebras and covered by law
tests:

- Composite sinks from `Serializable[T, String]` plus `Sink[F, String, D]`.
- Default `ReActLlmClient[F]` from `LlmClient[F]` via
  `ReActLlmClient.fromLlmClient(ReActToolPolicy.firstToolWins)`, with text
  responses becoming final answers, empty tool lists failing, and non-empty
  tool lists choosing the first tool call by named policy.
- `LlmClient[F]` from `ToMessages`, `LlmRequestCreator`, `LlmExecutor`, and
  `LlmResponseParser`.
- Command routers from an ordered command list plus a fallback command.
- Render writers from layout/rendering algebras, with `ScreenWriterF[F, A]`
  preserving the effect boundary.
- Effect-aware tool execution from synchronous `ToolExecutor[T]` plus an
  adapter-supplied suspension strategy such as `IO.blocking`.

## Behaviors That Stay Explicit

The following behavior should not be hidden behind generic algebras:

- LLM provider semantics, including model-specific request formats, native tool
  behavior, fallback parsing, and multi-tool policies.
- Sandbox execution semantics, including Docker/process ownership, lifecycle
  failures, and command isolation.
- Terminal frontend loops, including redraw timing, keyboard ownership, and
  terminal restoration.
- Memory summarization quality, because correctness depends on model output and
  application-level fallback policy.
- ReAct stopping behavior, including budget checks, stagnation detection, and
  finish/conclude semantics.

## Law and Test Strategy

Shared algebra laws live in test-only helpers and deterministic MUnit suites:

- `PortLawChecks` holds reusable checks for serialization, update, append,
  registry, formatter, and renderer laws.
- `PortLawsSpec` covers state, registry, config, dispatcher, and serialization
  laws.
- `BackendPipelineSpec` covers fake protocol composition for LLM pipelines.
- `FormattableSpec`, `ReActStateSpec`, `ToolExecutionLawSpec`, and
  `EffectfulCapabilityLawSpec` cover focused domain laws.

Do not add Cats Discipline or ScalaCheck until deterministic fixtures stop
being enough. A property-testing dependency is justified only when the same law
needs broad generated coverage across multiple non-trivial instances.

## Migration Guidance

1. Add new ports to `docs/port-algebra.md` with their family, law sketch, and
   instance locations.
2. Prefer an existing shared shape only when it preserves the current domain
   contract without weakening types.
3. Keep domain facades stable unless a migration removes real caller
   complexity and has law coverage for old and new behavior.
4. Put provider-specific protocol details in adapter packages, not in `ports`.
5. Treat new derived behavior as additive first. Avoid breaking typeclass arity
   or implicit search paths without a separate migration plan.

## Consequences

- The codebase gains reusable law vocabulary without a broad port rewrite.
- Existing public APIs remain stable while consolidation candidates mature.
- Future refactors have concrete regression guards before changing behavior.
- Some duplication remains intentionally because it preserves domain meaning and
  keeps external-system contracts explicit.
