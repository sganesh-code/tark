# Port Algebra Simplification Report

- **Date:** 2026-07-14
- **Status:** Pre-implementation analysis
- **Inputs:** `docs/port-algebra.md`, `docs/adr-port-algebra-consolidation.md`, law tests, and current port/adapters code

## Executive Summary

Identifying the laws did produce useful consolidation insight, but the main
lesson is selective consolidation rather than a broad typeclass rewrite.

The codebase has three different kinds of abstractions:

1. **Pure algebraic shapes** that can be safely consolidated:
   `Updatable`, `Appendable`, `Registry`, `Dispatcher`, `Serializable`,
   `Formattable`, and `CellRenderer`.
2. **Composable protocol pipelines** that can define higher-order behavior:
   `ToMessages`, `LlmRequestCreator`, `LlmExecutor`, `LlmResponseParser`,
   `LlmClient`, and `ReActLlmClient`.
3. **Domain/effect services** that should stay explicit:
   sandbox execution, terminal frontend loops, memory summarization quality,
   provider-specific LLM behavior, and ReAct stopping semantics.

The highest-value simplification is not to make everything look like Cats
typeclasses. It is to formalize a few higher-order constructors that remove
manual orchestration code while preserving explicit domain policies.

## Main Insights From Laws and Tests

### 1. Registry is the Cleanest Consolidation Candidate

`ToolRegistry[C]` follows the exact laws of a map-like registry:

- lookup after register,
- last-write-wins,
- unrelated-entry preservation,
- missing lookup returns `None`.

This maps directly to `Registry[C, String, Tool]`. Keeping `ToolRegistry[C]` as
a compatibility facade is still useful, but future generic helpers should target
`Registry`.

**Simplification potential:** medium. It reduces duplicate registry logic and
enables helpers such as `registerAll`, `lookupOrRaise`, and registry-backed
tool execution.

### 2. State Algebras Are Useful, but Domain Facades Still Matter

`ContextOps`, `ConfigOps`, and `ReActStateOps` expose reusable shapes:

- `Context.memory` and `Context.agentState` are `Updatable`.
- `Context.history` and `ReActState.steps` are `Appendable`.
- config fields are `Updatable` only when wrapped by field-specific types such
  as `ModelId` and `BaseUrl`.

The laws show that these abstractions are valid, but replacing the public
facades wholesale would lose domain readability. `markDone`,
`updateLastObservation`, budget checks, and memory semantics are not generic
state operations.

**Simplification potential:** medium. Use shared shapes for helpers and tests,
not as a forced replacement for every domain method.

### 3. LLM Protocol Composition Is the Best Higher-Order Win

The strongest result is the backend pipeline:

```scala
SystemPrompt | List[Interaction] | UserPrompt => List[Msg]
List[Msg]                                   => Req
Req                                         => F[Either[String, Res]]
Res                                         => Either[String, List[ToolCallRequest]]
```

The law tests show that this composes predictably:

- message order is preserved,
- executor errors bypass parsing,
- parser errors are preserved,
- multiple tool calls are not dropped by `LlmClient`.

`OllamaLlmClient` still manually repeats the pipeline steps even though
`LlmPipeline.client` can derive the same coarse client from the smaller
instances.

**Simplification potential:** high. Adapter clients can become mostly protocol
instances plus a call to the generic pipeline constructor.

### 4. ReAct Derivation Is Valid, but Policy Must Be Explicit

`ReActLlmClient[F]` can be derived from `LlmClient[F]`, but the derivation
contains policy:

- text responses become final answers,
- empty tool lists fail,
- non-empty tool lists select the first call.

The law tests make this policy visible. That means the higher-order function is
safe only if the policy remains named and configurable.

**Simplification potential:** medium-high. A policy parameter would make the
default derivation reusable without hiding domain behavior.

### 5. Command Handling Has Repeated Transition Patterns

Slash commands repeat several state/session operations:

- append `Message.User(state.prompt)`,
- append a system response,
- clear the prompt,
- write updated context through `Sink`,
- summarize history with fallback memory updates.

The router laws support a generic dispatcher, but the bigger simplification is
a small set of domain transition helpers, not a more abstract router.

**Simplification potential:** high for `SlashCommand.scala`, moderate for the
whole codebase.

### 6. Effectful Ports Need Contract Helpers, Not Pure Algebra Replacement

`Sink`, `TraceWriter`, `SessionProvider`, `BackendProvider`, `SandboxManager`,
and `ScreenWriter` have useful laws around sequencing, resource boundaries, and
error representation. They should not be collapsed into pure typeclasses.

**Simplification potential:** low-medium. The value is helper constructors and
resource-safe combinators, not replacing these ports.

## Ranked Consolidation Opportunities

| Rank | Opportunity | Impact | Risk | Confidence | Recommendation |
| --- | --- | --- | --- | --- | --- |
| 1 | Derive adapter `LlmClient` implementations from `LlmPipeline.client` | High | Low | High | Do first. It directly removes repeated pipeline orchestration. |
| 2 | Add command/chat transition helpers for slash commands | High | Medium | High | Do after tests characterize command output. This reduces repeated `ChatState` and `Session` copy logic. |
| 3 | Add named ReAct derivation policy for `LlmClient => ReActLlmClient` | Medium-high | Medium | High | Keep the current default but make first-tool/empty-list behavior explicit and testable. |
| 4 | Standardize registry helpers on `Registry[C, K, V]` | Medium | Low | High | Add helpers while retaining `ToolRegistry` facade. |
| 5 | Add state helper functions over `Updatable` and `Appendable` | Medium | Medium | Medium | Useful for internal transitions, but avoid replacing readable domain APIs. |
| 6 | Add render/write composition helpers around `Layout`, `CellRenderer`, and `ScreenWriterF` | Low-medium | Low | Medium | Good cleanup, but less central to current complexity. |
| 7 | Generalize all effectful services into common typeclasses | Low | High | High | Do not pursue. It would hide important domain/resource semantics. |

## Higher-Order Functions Worth Considering

These are candidates for a future implementation plan. They are intentionally
small and policy-explicit.

### 1. LLM Client Constructor

Already present as `LlmPipeline.client`.

Expected use:

```scala
val client: LlmClient[F] =
  LlmPipeline.client[F, Msg, Req, Res](modelName, format)
```

Potential simplification:

- `OllamaLlmClient.getCompletion` can delegate to this constructor instead of
  manually building messages, requests, execution, and parsing.
- New providers only implement the four protocol algebras.

### 2. ReAct Client Constructor With Policy

Current behavior is encoded in the default `ReActLlmClient` given. Consider a
named policy:

```scala
trait ReActToolPolicy {
  def choose(calls: List[ToolCallRequest]): Either[String, ToolCallRequest]
}

def fromLlmClient[F[_]: Sync](
  policy: ReActToolPolicy
)(using client: LlmClient[F]): ReActLlmClient[F]
```

Potential simplification:

- Keeps the derivation reusable.
- Makes first-tool selection explicit.
- Enables future policies without changing `LlmClient`.

### 3. Registry Helpers

```scala
def registerAll[C, K, V](container: C, entries: Iterable[(K, V)])
  (using Registry[C, K, V]): C

def lookupOrRaise[F[_]: MonadThrow, C, K, V](container: C, key: K, error: => Throwable)
  (using Registry[C, K, V]): F[V]
```

Potential simplification:

- Tool registration and lookup code can be expressed once.
- `ToolRegistry` can remain as the domain facade.

### 4. State Transition Helpers

```scala
def modify[A, B](source: A)(f: B => B)(using Updatable[A, B]): A

def appendAll[A, B](source: A, values: Iterable[B])(using Appendable[A, B]): A
```

Potential simplification:

- Repeated immutable copy chains become readable.
- Law tests already cover update composition and append ordering.

Risk:

- Overuse can make domain code less clear. Prefer these in shared helpers, not
  everywhere.

### 5. Chat State Transition Helpers

The slash command code would benefit from domain-specific helpers:

```scala
def userAndSystem(state: ChatState, systemMessage: String): ChatState
def clearPrompt(state: ChatState): ChatState
def appendSystem(state: ChatState, message: String): ChatState
```

Potential simplification:

- Reduces repeated `state.copy(history = ..., prompt = "")` blocks.
- Keeps command code focused on command semantics.

This is probably more valuable than trying to make slash commands fully generic.

### 6. Summarize-And-Persist Helper

`ExitCommand` and `ClearCommand` both summarize history, append/fallback
episodic memory, and write context.

Candidate shape:

```scala
def summarizeHistoryWithFallback[F[_]: MonadThrow](
  session: Session,
  reason: String
)(using EpisodicMemorySummarizer[F]): F[Memory]
```

Potential simplification:

- Consolidates error fallback behavior.
- Makes timestamp/error policy visible.
- Reduces divergence between `/exit` and `/clear`.

### 7. Render Writer Constructor

```scala
def screenWriterFromLayout[F[_], S](
  layout: Layout[S],
  writer: ScreenWriterF[F, Screen]
): (S, Int, Int, PrintWriter) => F[Unit]
```

Potential simplification:

- Useful if more frontends or screen targets are added.
- Lower priority today because rendering duplication is limited.

## What Not To Consolidate

Do not flatten the following into generic algebras:

- `SandboxManager`: lifecycle and failure semantics are adapter-specific.
- `Frontend`: event loops and terminal ownership are not generic dispatch.
- `EpisodicMemorySummarizer`: quality and fallback policy are domain behavior.
- `ReActExecutor`: convergence, stagnation, and stop reasons are domain logic.
- Provider-native `ReActStrategy`: request/response details belong in adapters.

The laws here are still valuable, but they should drive characterization tests
and resource-safe helpers rather than broad abstraction.

## Suggested Pre-Implementation Decision Gates

Before committing to an implementation plan, answer these questions:

1. Should `OllamaLlmClient` be refactored to delegate to `LlmPipeline.client`
   as the first proof of simplification?
2. Should the ReAct derivation policy remain hard-coded as first-tool-wins, or
   should policy become a small named dependency?
3. Should command transition helpers live under `ports/ui`, `ports/tool`, or a
   new small module such as `ports/tool/ChatTransitions.scala`?
4. Should `Registry` become the preferred internal helper target while
   `ToolRegistry` remains the public facade?
5. Should `ConfigOps.withUpdatedConfig(Map[String, Any])` be left alone for
   compatibility, with typed `Updatable[Config, Field]` used only for new code?

## Recommended Next Report-To-Plan Path

If this analysis is accepted, the next implementation plan should be narrow:

1. Refactor `OllamaLlmClient` to use `LlmPipeline.client`.
2. Extract chat command transition helpers and summarize/persist helper.
3. Add named ReAct tool-call policy while preserving the current default.
4. Add registry helper functions for internal use.
5. Stop there and reassess before touching domain facades.

This sequence targets simplification with low blast radius. It avoids a broad
typeclass migration while using the laws as regression guardrails.

## Implementation Outcome

The narrow simplification pass completed the high-confidence items without a
broad typeclass migration.

Changed code surfaces:

- `OllamaLlmClient` now delegates its standard completion path to
  `LlmPipeline.client`, while keeping Ollama request creation, STTP execution,
  error strings, parser fallback behavior, model defaults, and JSON format
  selection unchanged.
- `ReActLlmClient` now exposes `ReActToolPolicy` and
  `ReActLlmClient.fromLlmClient(policy)`. The default behavior remains
  `ReActToolPolicy.firstToolWins`.
- Slash commands now use `ChatTransitions` for common user/system/prompt
  transitions and `SessionMemoryTransitions` for `/exit` and `/clear`
  summarization, fallback summary creation, and persistence.
- `RegistryOps` adds `registerAll` and `lookupOrRaise` over
  `Registry[C, K, V]` while `ToolRegistry[C]` remains the public domain facade.
- `StateAlgebraOps` adds pure `modify`, `set`, and `appendAll` helpers over
  `Updatable` and `Appendable` without replacing domain APIs.

What paid off:

- The LLM pipeline refactor removed duplicate orchestration with low risk
  because the existing protocol instances stayed unchanged.
- Naming `ReActToolPolicy.firstToolWins` made an existing domain policy
  explicit and testable without changing default behavior.
- `ChatTransitions` and `SessionMemoryTransitions` reduced the highest-value
  slash-command duplication while preserving command text and return semantics.
- Registry and state helpers are useful as law-tested building blocks, but
  they are most valuable in helper modules rather than as a blanket migration.

What should stop here:

- Do not replace `ContextOps`, `ConfigOps`, `ToolRegistry`, or
  `ReActStateOps` wholesale. The shared helpers are smaller than the domain
  facades and should stay additive.
- Do not generalize `SandboxManager`, `Frontend`, `EpisodicMemorySummarizer`,
  provider-native `ReActStrategy`, or `ReActExecutor`; their behavior remains
  domain or adapter specific.
- Do not start render/write helper work yet. The current rendering duplication
  is limited, and `Layout`, `CellRenderer`, and `ScreenWriterF` still carry
  distinct contracts. Revisit only if another frontend or screen target creates
  repeated render/write composition code.
