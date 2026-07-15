# Tark Port Algebra Inventory

This document classifies the project ports and typeclass-style abstractions before
any consolidation refactor. The goal is to make the intended algebra and laws
explicit enough that later changes can be tested against current behavior.

## Algebra Families

| Algebra | Source | Family | Cats analogy | Law sketch | Instance locations |
| --- | --- | --- | --- | --- | --- |
| `Serializable[T, R]` | `@src/main/scala/com/tark/ports/shared/serialization/Serializable.scala` | Encoder/decoder-style natural transformation | Encoder-like, contravariant in practical use | Serialization is deterministic for equal inputs, has no hidden mutation, and is compatible with any downstream `Sink[F, R, D]`. | `@src/main/scala/com/tark/application/instances/ContextInstances.scala` |
| `Sink[F, T, D]` | `@src/main/scala/com/tark/ports/shared/serialization/Sink.scala` | Monoidal writer/sink | Writer/consumer; `T => F[Unit]` at a destination | Writes sequence in `F`; the composite sink is equivalent to `serialize` followed by a `String` sink; destination overwrite/append behavior must be documented by the instance. | `@src/main/scala/com/tark/ports/shared/serialization/Sink.scala` |
| `ConfigOps[C]` | `@src/main/scala/com/tark/ports/shared/config/ConfigOps.scala` | State accessor/updater | Lens-like getters and updater | Getters reflect stored config; `withUpdatedConfig` preserves unspecified fields; disjoint updates commute. | `@src/main/scala/com/tark/application/instances/ConfigInstances.scala` |
| `ContextOps[C]` | `@src/main/scala/com/tark/ports/outbound/context/ContextOps.scala` | State accessor/updater | Lens/state algebra | Get after update observes the update; unrelated fields are preserved; `addInteraction` appends in order; memory/state updates compose like functions. | `@src/main/scala/com/tark/application/instances/ContextInstances.scala` |
| `ToolRegistry[C]` | `@src/main/scala/com/tark/ports/shared/tool/ToolRegistry.scala` | Registry/map algebra | Map-like lookup and insert | Lookup after register returns the registered tool; same-name registration is last-write-wins; unrelated tools are preserved; missing lookup returns `None`. | `@src/main/scala/com/tark/application/instances/ContextInstances.scala` |
| `ToolExecutor[T]` | `@src/main/scala/com/tark/ports/shared/tool/ToolExecutor.scala` | Interpreter/executor | Algebra interpreter for a tool value | A valid tool can be executed; validation is stable for the same tool; execution result is determined by the tool interpreter and supplied context. | `@src/main/scala/com/tark/application/tools/ToolInstances.scala` |
| `ToToolDescription[T]` | `@src/main/scala/com/tark/ports/shared/tool/ToToolDescription.scala` | Encoder/decoder-style natural transformation | Encoder-like projection to schema | The description names the same logical tool, exposes required fields consistently, and is deterministic for a tool value. | `@src/main/scala/com/tark/ports/shared/tool/ToToolDescription.scala` |
| `SlashCommand[F]` | `@src/main/scala/com/tark/ports/inbound/tool/SlashCommand.scala` | Command/router | Kleisli command, `(State, Session) => F[Option[(State, Session)]]` | Command names are stable; execution returns either exit or a new state/session; command effects are represented in `F`. | `@src/main/scala/com/tark/ports/inbound/tool/SlashCommand.scala` |
| `SlashCommandRouter[F]` | `@src/main/scala/com/tark/ports/inbound/tool/SlashCommandRouter.scala` | Command/router | Dispatcher over commands | Exact command match wins; fallback handles unknown commands; routing is deterministic for a fixed command list. | `@src/main/scala/com/tark/ports/inbound/tool/SlashCommandRouter.scala` |
| `InputProcessor[F]` | `@src/main/scala/com/tark/ports/inbound/tool/InputProcessor.scala` | Command/router | Program interpreter in `F` | Slash inputs route to slash commands; non-slash inputs enter ReAct execution; returned state/session are the only durable state transitions aside from declared sinks/traces. | `@src/main/scala/com/tark/ports/inbound/tool/InputProcessor.scala` |
| `ToolCallDetector` | `@src/main/scala/com/tark/ports/shared/tool/ToolCallDetector.scala` | Parser/renderer | Partial parser | Recognized patterns parse deterministically; unrecognized text returns `None`; parsed tool names and arguments preserve source intent. | `@src/main/scala/com/tark/ports/shared/tool/ToolCallDetector.scala` |
| `LlmClient[F]` | `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala` | Effectful service | Not equivalent to a standard Cats typeclass | Text responses and tool-call responses are represented explicitly; errors and provider effects stay in `F` and `Either`; behavior depends on provider contract. | `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala`, `@src/main/scala/com/tark/adapters/backend/ollama/OllamaLlmClient.scala` |
| `ToMessages[A, Msg]` | `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala` | Encoder/decoder-style natural transformation | Encoder-like, list homomorphism | List instance preserves order; empty list maps to empty messages; concatenation distributes through conversion. | `@src/main/scala/com/tark/adapters/backend/ollama/OllamaProtocol.scala` |
| `LlmRequestCreator[Msg, Req]` | `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala` | Encoder/decoder-style natural transformation | Builder from accumulated messages | Request creation is deterministic for the same model, messages, and format; message order is preserved. | `@src/main/scala/com/tark/adapters/backend/ollama/OllamaLlmClient.scala` |
| `LlmExecutor[F, Req, Res]` | `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala` | Interpreter/executor | Kleisli arrow `Req => F[Either[String, Res]]` | Provider errors are represented as `Left`; successful responses are represented as `Right`; request execution stays in `F`. | `@src/main/scala/com/tark/adapters/backend/ollama/OllamaLlmClient.scala` |
| `LlmResponseParser[Res]` | `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala` | Parser/renderer | Decoder-like projection | Parsing is deterministic; malformed provider payloads become `Left`; valid tool-call payloads preserve tool name and arguments. | `@src/main/scala/com/tark/adapters/backend/ollama/OllamaLlmClient.scala` |
| `BackendProvider[F]` | `@src/main/scala/com/tark/ports/outbound/backend/BackendProvider.scala` | Resource provider | `Resource` provider | Clients are acquired and released through `Resource`; provider selection is explicit in the instance. | `@src/main/scala/com/tark/bootstrap/OllamaRuntime.scala` |
| `ReActLlmClient[F]` | `@src/main/scala/com/tark/ports/outbound/react/ReActLlmClient.scala` | Effectful service | Not equivalent to a standard Cats typeclass | Final answers and tool calls are distinguished; default derivation from `LlmClient[F]` preserves text responses and selects a tool-call policy explicitly. | `@src/main/scala/com/tark/ports/outbound/react/ReActLlmClient.scala`, `@src/main/scala/com/tark/adapters/backend/ollama/OllamaReActLlmClient.scala` |
| `ReActExecutor[E, F]` | `@src/main/scala/com/tark/ports/outbound/react/ReActExecutor.scala` | Interpreter/executor | Kleisli-style interpreter | Executing the same executor under the same effects follows the same transition rules; stop reasons are represented in `ReActState`. | `@src/main/scala/com/tark/application/react/DefaultReActExecutor.scala` |
| `ReActStateOps[S]` | `@src/main/scala/com/tark/ports/shared/react/ReActStateOps.scala` | State accessor/updater | State/lens algebra | `addStep` appends immutably; `updateLastObservation` is a no-op for empty steps; `markDone` preserves steps and marks completion; budget checks are monotonic. | `@src/main/scala/com/tark/ports/shared/react/ReActStateOps.scala` |
| `ReActStrategy[F]` | `@src/main/scala/com/tark/ports/outbound/react/ReActStrategy.scala` | Parser/renderer | Protocol-specific interpreter | Prepared requests include the intended model/tools; parsing preserves final-answer vs tool-call distinction; current implementation is Ollama-shaped and should be treated as adapter-specific. | `@src/main/scala/com/tark/ports/outbound/react/ReActStrategy.scala` |
| `TraceWriter[F]` | `@src/main/scala/com/tark/ports/outbound/trace/TraceWriter.scala` | Monoidal writer/sink | Writer/consumer | Trace output reflects serialized state; write failures remain in `F`; normal filenames do not overwrite prior traces. | `@src/main/scala/com/tark/ports/outbound/trace/TraceWriter.scala` |
| `EpisodicMemorySummarizer[F]` | `@src/main/scala/com/tark/ports/outbound/memory/EpisodicMemorySummarizer.scala` | Effectful service | Not equivalent to a standard Cats typeclass | Summary output is effectful and provider-dependent; failures must be represented in `F`; callers define fallback behavior. | `@src/main/scala/com/tark/adapters/backend/ollama/OllamaEpisodicMemorySummarizer.scala`, `@src/main/scala/com/tark/bootstrap/OllamaRuntime.scala` |
| `SessionProvider[F]` | `@src/main/scala/com/tark/ports/outbound/context/SessionProvider.scala` | Resource provider | `Resource` provider | Sessions are acquired and released through `Resource`; created session paths and context are valid for downstream sinks. | `@src/main/scala/com/tark/adapters/context/DefaultSessionProvider.scala` |
| `SandboxManager[F, S]` | `@src/main/scala/com/tark/ports/outbound/sandbox/SandboxManager.scala` | Effectful service | Not equivalent to a standard Cats typeclass | `start` precedes `execute`; `stop` releases resources; command execution effects and failures stay in `F`. | `@src/main/scala/com/tark/adapters/sandbox/local/LocalProcessSandbox.scala`, `@src/main/scala/com/tark/adapters/sandbox/docker/DockerSandboxManager.scala` |
| `Frontend[F]` | `@src/main/scala/com/tark/ports/outbound/ui/Frontend.scala` | Effectful service | Not equivalent to a standard Cats typeclass | `redraw` reflects a supplied state; `loop` owns frontend event progression; terminal or UI effects stay in `F`. | `@src/main/scala/com/tark/adapters/inbound/terminal/jline/JLineFrontend.scala` |
| `KeyboardHandler[F]` | `@src/main/scala/com/tark/ports/inbound/ui/KeyboardHandler.scala` | Command/router | Dispatcher over key commands | Key decoding is deterministic; custom shortcuts override defaults; unhandled control characters leave state/session unchanged. | `@src/main/scala/com/tark/ports/inbound/ui/KeyboardHandler.scala` |
| `ScreenWriter[A]` | `@src/main/scala/com/tark/ports/outbound/ui/ScreenWriter.scala` | Parser/renderer | Renderer to `IO[Unit]` | Full write renders every cell; delta write renders changed cells only when supported; output effects stay in `IO`. | `@src/main/scala/com/tark/adapters/ui/ScreenWriterInstances.scala` |
| `Formattable[A]` | `@src/main/scala/com/tark/ports/shared/ui/Formattable.scala` | Parser/renderer | Endomorphic styling transformation | Formatting with no options is identity; repeated identical formatting is idempotent; formatting preserves structure and text/glyph content. | `@src/main/scala/com/tark/ports/shared/ui/Formattable.scala`, `@src/main/scala/com/tark/ports/shared/ui/Layout.scala` |
| `Colorable[A]` | `@src/main/scala/com/tark/ports/shared/ui/Layout.scala` | Parser/renderer | Attribute projection | Color projection is deterministic and reflects the value's visible foreground color. | `@src/main/scala/com/tark/ports/shared/ui/Layout.scala` |
| `Glyph[A]` | `@src/main/scala/com/tark/ports/shared/ui/Glyph.scala` | Parser/renderer | Width/size measurement | Width is non-negative; default `String` width is additive over concatenation; `Char` width is one. | `@src/main/scala/com/tark/ports/shared/ui/Glyph.scala` |
| `CellRenderer[C, T]` | `@src/main/scala/com/tark/ports/shared/ui/CellRenderer.scala` | Parser/renderer | Renderer | Rendering is deterministic; rendered output preserves the source glyph; style/color encodings match the target renderer contract. | `@src/main/scala/com/tark/ports/shared/ui/CellRenderer.scala` |
| `Layout[S]` | `@src/main/scala/com/tark/ports/shared/ui/Layout.scala` | Parser/renderer | Renderer from state to screen | Rendered screen dimensions match requested dimensions; rendering is deterministic; wrapping preserves message style; rendering does not mutate source state. | `@src/main/scala/com/tark/ports/shared/ui/Layout.scala` |

## Consolidation Notes

The most promising consolidation candidates are the pure state and registry
algebras:

- `ContextOps`, `ConfigOps`, `ToolRegistry`, and parts of `ReActStateOps` all
  expose lens-like or map-like behavior.
- A local `Registry[C, K, V]` could model `ToolRegistry[C]` if it preserves the
  current last-write-wins and missing-key behavior.
- A local `Updatable[A, B]` or narrowly scoped accessor abstraction could model
  repeated `get` plus `update` pairs, but only if the result is clearer than the
  current domain-specific names.

The backend protocol algebras can likely define higher-order behavior without
replacing the existing coarse services:

- `ToMessages`, `LlmRequestCreator`, `LlmExecutor`, and `LlmResponseParser`
  compose into an `LlmClient[F]` pipeline.
- `LlmClient[F]` can derive a default `ReActLlmClient[F]`, but the multi-tool
  call policy must be explicit because it is domain behavior rather than a
  generic Cats law.

Some ports should remain explicit domain services:

- `LlmClient`, `ReActLlmClient`, `Frontend`, `SandboxManager`,
  `EpisodicMemorySummarizer`, and `BackendProvider` depend on external systems
  or resource lifecycles. Their contracts should be tested with characterization
  tests and fake interpreters, not treated as direct equivalents of `Functor`,
  `Applicative`, or `Monad`.

## Regression Guardrails

Before behavior refactors, add law and characterization tests for current
instances. The first test layer should use existing MUnit tests and deterministic
fixtures rather than adding property-testing dependencies. Later refactors should
be accepted only if the current laws still pass and any intentionally changed
laws are documented in an ADR.

## Pure Transformation and Rendering Laws

These laws are intended for ports whose observable behavior should be
deterministic and mostly referentially transparent. Some current implementations
operate on mutable structures such as `Screen`; law tests for those instances
should assert observable behavior and explicitly document mutation boundaries.

### `Serializable[T, R]`

- **Determinism:** serializing equal values produces equal serialized output.
- **No hidden mutation:** serialization does not mutate the source value.
- **Stable representation:** repeated serialization of the same value produces
  the same output unless the value itself has changed.
- **Sink compatibility:** for any `Sink[F, R, D]`, the composite
  `Sink[F, T, D]` is equivalent to `serializable.serialize(data)` followed by
  the underlying sink write.

### `ToMessages[A, Msg]`

- **Order preservation:** converted messages preserve source ordering.
- **Empty-list identity:** converting `List.empty[A]` produces
  `List.empty[Msg]`.
- **List homomorphism:** for lists `xs` and `ys`,
  `toMessages(xs ++ ys) == toMessages(xs) ++ toMessages(ys)`.
- **Element consistency:** the list instance delegates to the element instance
  exactly once per element and concatenates the results.

### `Formattable[A]`

- **Identity:** `formatted(None, None, None)` returns an observationally equal
  value.
- **Field preservation:** unspecified fields keep their previous values.
- **Last-write-wins:** applying two updates to the same field is equivalent to
  applying only the final update for that field.
- **Idempotence:** applying the same complete formatting options repeatedly is
  observationally equal to applying them once.
- **Structure preservation:** formatting preserves glyph/text content and the
  concrete shape of `Cell`, `Message`, and `Screen` values.

### `Glyph[A]`

- **Non-negative width:** `columns(value) >= 0`.
- **Default string additivity:** for the default `String` instance,
  `columns(a + b) == columns(a) + columns(b)`.
- **Default char width:** for the default `Char` instance, every character has
  width `1`.
- **Renderer consistency:** measured width should match the number of display
  cells consumed by the corresponding renderer for the supported character set.

### `Layout[S]`

- **Dimension preservation:** `render(state, width, height)` returns a `Screen`
  with exactly that `width` and `height`.
- **Determinism:** rendering the same state with the same config and dimensions
  produces the same observable cell grid.
- **Source-state preservation:** rendering does not mutate the input state.
- **Style preservation:** wrapping and rendering messages preserve foreground,
  background, and style attributes for visible message cells.
- **Bounds safety:** rendering does not write outside the screen bounds.

## State, Registry, and Configuration Laws

These laws cover model-focused algebras where operations should behave like
small lenses, append-only state transitions, or map registries. They are the
main safety net for any later attempt to consolidate one-off accessors into
shared abstractions.

### Consolidation Comparison

| Existing algebra | Shared shape | Notes |
| --- | --- | --- |
| `ContextOps[C]` | `Contains[C, B]`, `Updatable[C, B]`, `Appendable[C, Interaction]` | `getMemory`/`updateMemory` and `getAgentState`/`updateAgentState` are lens-like; `addInteraction` is append-like; `getContextTools` is read-only unless paired with `ToolRegistry`. |
| `ToolRegistry[C]` | `Registry[C, String, Tool]` | This is the cleanest consolidation candidate because it is already a map-style insert and lookup algebra over tool names. |
| `ConfigOps[C]` | `Contains[C, Field]`, field-specific `Updatable[C, Field]` | Getter behavior is simple, but `withUpdatedConfig(config, Map[String, Any])` is intentionally loose; field-specific update algebras are safer than generic `Any` maps. |
| `ReActStateOps[S]` | `Appendable[S, ReActStep]`, last-element update, marker/query | `addStep` is append-like, but `updateLastObservation`, `markDone`, and `isBudgetExceeded` are domain-specific enough to keep the facade. |

The prototype therefore uses small local abstractions for the reusable shapes
while retaining the existing domain-specific ports as compatibility facades.

### Prototype Decision

The prototype in `@src/main/scala/com/tark/ports/shared/algebra/StateAlgebras.scala`
is useful as a law-testing and exploration layer, but it should not replace the
existing domain ports yet.

- `Registry[Context, String, Tool]` is simpler than `ToolRegistry[Context]` and
  can express the same map-like behavior when the key is the tool name.
- `Appendable[Context, Interaction]` and `Appendable[ReActState, ReActStep]`
  capture the shared append behavior cleanly.
- `Updatable[Context, Memory]` and `Updatable[Context, Option[AgentState]]`
  are precise enough to describe the current context update semantics.
- `Config` requires field wrapper types such as `ModelId` and `BaseUrl` because
  multiple fields share the same primitive type; replacing `ConfigOps` directly
  with `Updatable[Config, String]` would weaken type safety.
- `ReActStateOps` still owns domain-specific behavior such as
  `updateLastObservation`, `markDone`, and `isBudgetExceeded`; only `addStep`
  naturally generalizes to `Appendable`.
- `StateAlgebraOps.modify`, `StateAlgebraOps.set`, and
  `StateAlgebraOps.appendAll` are shared internal transition helpers. They are
  appropriate inside small reusable helper modules, but they should not replace
  readable domain facades such as `ContextOps`, `ConfigOps`, and
  `ReActStateOps`.

Keep `ContextOps`, `ConfigOps`, `ToolRegistry`, and `ReActStateOps` as the
public facades until a later migration proves that the shared algebras reduce
caller complexity without hiding domain intent.

## LLM Pipeline Laws

The backend protocol algebras in `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala`
compose into a provider-independent pipeline:

```scala
SystemPrompt | List[Interaction] | UserPrompt => List[Msg]
List[Msg]                                   => Req
Req                                         => F[Either[String, Res]]
Res                                         => Either[String, List[ToolCallRequest]]
```

The concrete `LlmClient[F]` behavior is the composition of these smaller steps:

1. Convert the system prompt, prior interactions, and user prompt to provider
   messages with `ToMessages`.
2. Concatenate messages in system-history-user order.
3. Create the provider request with `LlmRequestCreator`.
4. Execute the request with `LlmExecutor`.
5. Parse successful responses with `LlmResponseParser`.
6. Preserve executor failures as `Left` without invoking the parser.

### Composed Pipeline Laws

- **Message ordering:** composed clients preserve system prompt messages first,
  history messages next, and user prompt messages last.
- **Message homomorphism:** history message conversion follows
  `ToMessages[List[A], Msg]` list homomorphism laws.
- **Executor error preservation:** if `LlmExecutor` returns `Left(error)`, the
  composed client returns the same `Left(error)` and does not parse a response.
- **Parser error preservation:** if `LlmResponseParser` returns `Left(error)`,
  the composed client returns that same `Left(error)`.
- **No dropped tool calls:** when the parser returns multiple tool calls, the
  composed `LlmClient[F]` returns the complete list.
- **Empty tools stability:** empty `tools` input should not change message
  ordering or request creation unless a provider-specific request creator
  explicitly documents tool-dependent behavior.

### Default `ReActLlmClient[F]` Derivation Laws

The default derivation in `@src/main/scala/com/tark/ports/outbound/react/ReActLlmClient.scala`
adapts a coarse `LlmClient[F]` into a single-step ReAct client:

- **Text response law:** `LlmClient` text responses (`Left(text)`) become
  `Right(ReActResponse(text, Left(text)))`.
- **Executor/parser error law:** errors represented by failed effects remain in
  `F`; ordinary `Left(text)` values are treated as final text responses by the
  current `LlmClient` contract.
- **Empty tool response law:** `Right(Nil)` from `LlmClient` becomes
  `Left("No tool calls returned")`.
- **Multi-tool policy:** `Right(toolCalls)` with one or more calls is resolved
  by `ReActToolPolicy`. The default `ReActToolPolicy.firstToolWins` chooses the
  first tool call for the ReAct step and drops the remaining calls by policy.
  This is domain behavior, not a generic pipeline law; callers that need a
  different selection rule should use `ReActLlmClient.fromLlmClient(policy)`,
  and callers that need all tool calls should operate at the `LlmClient[F]`
  level.

## ReAct Strategy Laws

`ReActStrategy[F, Msg, Req, Res]` is protocol-generic at the port boundary.
Provider-specific strategies, such as Ollama JSON and native tool-calling
strategies, belong in adapter packages where the concrete `Msg`, `Req`, and
`Res` types are known.

- **Request completeness:** `prepareRequest` includes the supplied model and all
  messages in their existing order.
- **Tool advertisement:** when a strategy prepares native tool metadata, the
  advertised tools are derived from the supplied `tools` list plus any
  explicitly documented strategy-owned control tools such as `conclude_task`.
- **Answer/tool distinction:** `parseResponse` preserves the distinction between
  final answers (`Left(finalAnswer)` inside `ReActResponse.action`) and tool
  calls (`Right(ToolCallRequest)` inside `ReActResponse.action`).
- **Parser failure representation:** parser failures or malformed model output
  are represented in the returned `F[Either[String, ReActResponse]]`; a strategy
  may choose a final-answer fallback only if that behavior is documented by the
  adapter.
- **No unadvertised tool invention:** strategy output should not invent tool
  names outside the supplied registry or documented control tools.
- **Adapter ownership:** strategies tied to concrete protocol models such as
  `OllamaMessage`, `OllamaRequest`, and `OllamaResponse` must live under the
  matching adapter namespace, not under `ports`.

## Command and Input Dispatch Laws

Tark currently has three related dispatch paths:

1. `KeyboardHandler[F]` converts raw key codes to `Key` values. Custom
   shortcuts are tried first via `customShortcuts.orElse(Shortcuts.default[F])`;
   unmatched printable characters update the prompt, and unmatched control
   characters leave state/session unchanged.
2. `InputProcessor[F]` trims input and dispatches inputs starting with `/` to
   `SlashCommandRouter[F]`; all other inputs enter the ReAct execution path.
3. `SlashCommandRouter[F]` extracts the first whitespace-delimited token as the
   command name, finds the first registered `SlashCommand[F]` with that name,
   and falls back to `UnknownCommand` when no command matches.

### Router Laws

- **Exact route match wins:** a slash route matches only when the first token is
  equal to the command name.
- **First-match ordering:** when multiple commands have the same name, the first
  command in the registered list wins.
- **Fallback law:** unknown slash commands are handled by `UnknownCommand`.
- **Deterministic lookup:** a fixed command list and fixed input always choose
  the same command.
- **State transition boundary:** command execution must return the next
  state/session explicitly; input state is not mutated in place.
- **Keyboard override law:** custom keyboard shortcuts override defaults for the
  same key, while unhandled keys fall back to default behavior.

### Dispatch Abstraction Decision

The prototype in `@src/main/scala/com/tark/ports/shared/algebra/DispatchAlgebras.scala`
captures the reusable "first matching route plus fallback" pattern. It is useful
for law tests and future experimentation, but the public APIs should remain
domain-specific for now:

- `SlashCommand[F]` carries command names and `ChatState`/`Session` semantics.
- `KeyboardShortcuts[F]` is intentionally a `PartialFunction[Key, KeyAction[F]]`
  so custom shortcuts can override defaults with normal Scala composition.
- `InputProcessor[F]` owns the larger slash-vs-ReAct decision and should not be
  hidden behind a generic dispatcher until ReAct execution itself is abstracted.

`CommandClassifier` should remain a small helper for now. It is deterministic
and easy to test, but making it a typeclass would add indirection without a
current second classifier implementation.

## Tool Execution Laws

Current tool execution spans five pieces:

1. `Tool` in `@src/main/scala/com/tark/domain/tool/Tool.scala` stores a tool
   name, synchronous `ToolContext => String` interpreter, and tool type.
2. `ToolExecutor[T]` in `@src/main/scala/com/tark/ports/shared/tool/ToolExecutor.scala`
   validates a tool value and executes it synchronously.
3. `ToToolDescription[T]` in
   `@src/main/scala/com/tark/ports/shared/tool/ToToolDescription.scala` projects a tool
   to the function schema advertised to an LLM.
4. `ToolValidator` in `@src/main/scala/com/tark/ports/shared/tool/ToolValidator.scala`
   validates JSON tool-call arguments against that schema.
5. ReAct execution in
   `@src/main/scala/com/tark/application/react/DefaultReActExecutor.scala`
   looks up a tool, describes it, validates the model arguments, then executes
   the synchronous tool inside `F.blocking`.

### Tool Algebra Laws

- **Validate-before-execute:** callers should execute a tool only after
  `ToolExecutor.validate(tool) == true`.
- **Description-name coherence:** `ToToolDescription[Tool].describe(tool)` must
  describe the same logical tool name as `tool.name`.
- **Schema-before-execute:** successful `ToolValidator.validate(definition,
  input)` means required fields are present and have compatible JSON types
  before execution receives a `ToolContext`.
- **Registry executable lookup:** after a tool is registered in a
  `ToolRegistry`, lookup by name returns an executable tool value.
- **Effect boundary:** blocking or effectful tool execution must be selected by
  an adapter/caller. Pure model companions should not hide blocking behavior in
  an implicit instance.

### Effect-Aware Prototype Decision

The prototype `ExecutableTool[F, T]` in
`@src/main/scala/com/tark/ports/shared/tool/ExecutableTool.scala` is an additive bridge
from the current synchronous executor to effectful execution. It keeps the
existing `ToolExecutor[T]` public API unchanged and requires callers to provide
the suspension strategy, such as `IO.blocking`, at the adapter boundary. This is
preferable to adding a global `ToolExecutor[F[_], T]` because it avoids a
breaking type-arity change to the existing `ToolExecutor[T]` port.

## Effectful Capability and Resource Laws

Effectful ports cannot be treated as pure Cats-style algebras, but they can have
observable contracts around sequencing, resource safety, and error propagation.

### `Sink[F, T, D]`

- **Sequencing:** writes occur in the order imposed by `F`.
- **Composite equivalence:** the generic `Sink[F, T, D]` derived from
  `Serializable[T, String]` and `Sink[F, String, D]` is equivalent to
  serializing first and then writing the resulting string.
- **Destination semantics:** each concrete sink documents whether it overwrites,
  appends, truncates, or mutates the destination.
- **Error representation:** write failures are represented by the effect `F`.

### `TraceWriter[F]`

- **Serialization inclusion:** trace output contains the serialized ReAct state.
- **Effectful failure:** trace write failures remain in `F`.
- **File isolation:** under normal clock behavior, generated trace filenames do
  not overwrite previous traces for the same session directory.
- **Directory safety:** trace writers create required parent directories or fail
  explicitly in `F`.

### `SessionProvider[F]` and `BackendProvider[F]`

- **Resource acquisition:** resources are acquired through `Resource`.
- **Release guarantee:** release actions run when the resource scope exits.
- **Single-use acquisition:** each `Resource` use acquires a fresh resource
  value unless the implementation explicitly documents sharing.
- **Failure safety:** acquisition failures prevent use; release failures remain
  represented in `F`.

### `SandboxManager[F, S]`

- **Lifecycle order:** `start` precedes `execute`, and `stop` is called after the
  managed use scope.
- **Effectful execution:** command output and command failures are represented
  in `F`.
- **Failure policy:** failed `execute` does not imply the manager is unusable
  unless the adapter documents that policy.
- **Adapter boundary:** process, Docker, VM, or remote execution concerns belong
  in adapter implementations.

### `ScreenWriter[A]`

`ScreenWriter[A]` currently fixes its effect to `IO`. The non-breaking prototype
`ScreenWriterF[F, A]` in `@src/main/scala/com/tark/ports/outbound/ui/ScreenWriterF.scala`
shows the effect-generic shape:

- **Full-write law:** `write` renders the whole value to the target writer.
- **Delta fallback law:** the default `writeDelta` may delegate to `write`.
- **Effect boundary:** all output effects are represented in `F`.
- **Compatibility:** existing `ScreenWriter[A]` instances can be lifted to
  `ScreenWriterF[IO, A]` without changing existing callers.

### `ContextOps[C]`

- **Get-after-update:** reading a field after updating it observes the updated
  value.
- **Unrelated-field preservation:** updating memory, agent state, or history
  does not change unrelated context fields such as tools or sandbox.
- **Append order:** `addInteraction` appends to the end of history and preserves
  existing interaction order.
- **Update composition:** applying `updateMemory(context, f)` and then
  `updateMemory(_, g)` is observationally equivalent to applying
  `updateMemory(context, f.andThen(g))`. The same rule applies to
  `updateAgentState`.
- **No source mutation:** update operations return a new context and leave the
  source context observationally unchanged.

### `ToolRegistry[C]`

- **Lookup-after-register:** looking up a registered tool by name returns that
  tool.
- **Last-write-wins:** registering another tool with the same name replaces the
  prior mapping for that name.
- **Unrelated-tool preservation:** registering one tool does not remove tools
  registered under other names.
- **Missing lookup:** looking up a name that has never been registered returns
  `None`.
- **No source mutation:** registering a tool returns a new context and leaves the
  source registry observationally unchanged.

### `ReActStateOps[S]`

- **Append immutability:** `addStep` appends exactly one step and leaves the
  source state unchanged.
- **Observation no-op on empty state:** `updateLastObservation` on a state with
  no steps returns a state with no steps.
- **Last-step observation:** `updateLastObservation` updates only the latest
  step and preserves earlier steps.
- **Done preservation:** `markDone` sets `done == true`, records the supplied
  reason, and preserves the existing step list.
- **Budget monotonicity:** once the budget is exceeded, adding more steps keeps
  it exceeded.

### `ConfigOps[C]`

- **Getter consistency:** each getter returns the corresponding value stored in
  the config.
- **Unspecified-field preservation:** `withUpdatedConfig` changes only fields
  present in the update map.
- **Disjoint update commutativity:** applying disjoint update maps in either
  order produces the same config.
- **Last-write-wins:** repeated updates to the same field use the final supplied
  value.
- **No source mutation:** updating config returns a new value and leaves the
  source config unchanged.
