# Tark: An Educational LLM Agent Harness

Tark is an academic reference playground and learning implementation for **LLM Harness Engineering** built in **Scala 3** using functional programming principles with **Cats Effect**, **FS2**, and **Sttp**.

Rather than a closed or complex production framework, Tark is designed as a transparent, highly accessible reference model. Its core purpose is to help developers, students, and AI researchers learn how to build, control, and inspect AI agent loops from scratch. Based on modern software agent research, Tark acts as a controlled state machine: the LLM proposes reasoning and actions, but the Scala runtime retains complete control over state, tools, validation, verification, and safety boundaries.

---

## 📖 Table of Contents
1. [High-Level Concepts](#-high-level-concepts)
2. [System Architecture](#-architecture--component-diagram)
3. [State Machine & Convergence Rules](#-state-machine--convergence-rules)
4. [Frontend Architecture](#-frontend-architecture)
5. [Backend Integration](#-backend-integration)
6. [Developer Quick Start](#-developer-quick-start)
7. [Contributor Guide](#-contributor-guide)
8. [Testing Reference](#-testing-reference)
9. [Architecture References](#-architecture-references)

---

## 💡 High-Level Concepts

Tark operates on several core architectural design principles to ensure reliability and safety:

*   **Controlled State Machine:** Unlike raw "agent loops" that delegate control to the model, Tark’s runtime manages state transitions, parses outputs, enforces safety bounds, and halts execution when necessary.
*   **Structured Memory Layers:**
    *   **Working Memory (`AgentState`):** Fast, in-memory scratchpad tracking the current message transcript, candidate answer, goal, deliverable, open questions, assumptions, and tool results.
    *   **Episodic Memory:** Stores summaries of prior sessions to resume context across chat startups.
    *   **Procedural Memory:** Holds registered recipes and skills.
    *   **Semantic Memory:** Handles document reference indexes and facts.
*   **Execution Sandbox:** All tools run in an isolated environment (such as a Docker container) rather than directly on the developer's host machine.
*   **Trajectory Visibility:** The chat loop keeps assistant content, tool calls, and tool results explicit in the terminal and session history.

---

## 🏗️ Architecture & Component Diagram

Tark is built using a decoupled port-and-adapter architecture:

```
                          +-------------------------+
                          |   Terminal / User I/O   |
                          +------------+------------+
                                       |
                                       v
                          +------------+------------+
                          |      JLineFrontend      |
                          +------------+------------+
                                       |
                                       v
                          +------------+------------+
                          |    AgentBackend Port    |
                          +------------+------------+
                                       |
                                       v
                          +------------+------------+
                          |  DefaultAgentBackend    |
                          +-----+-----------+-------+
                                |           |
                    +-----------+           +----------------+
                    v                                        v
          +---------+---------+                    +---------+---------+
          | CommandExecutor   |                    |   OllamaLlmClient |
          +---------+---------+                    +---------+---------+
                    |                                        |
                    v                                        v
          Docker/local shell                     Buffered + streaming LLM

          DefaultAgentBackend also persists updated session context through Sink.
```

### Component Breakdown
1.  **`TarkCLI` / `TarkApp`:** Loads runtime configuration, creates a session, selects concrete adapters, and starts the terminal frontend.
2.  **`com.tark.ui`:** Portable frontend language: `AgentTask`, `AgentAction`, terminal reader/writer typeclasses, neutral styles, spinner contracts, and input results. This package intentionally has no JLine dependency.
3.  **`AgentBackend[F]`:** Frontend-facing port. The frontend sends user input and receives a stream of status-bearing `AgentTask`s whose actions update the terminal.
4.  **`DefaultAgentBackend`:** Application implementation of `AgentBackend`. It routes slash commands, streams assistant output, executes tools, updates session memory, and persists context.
5.  **`Prompt` / `LLMResponse` / `LlmStreamEvent` / `ToolCall` / `ToolResult`:** LLM protocol values and streaming events used to model assistant text, tool-call deltas, completed tool calls, and fallback behavior.
6.  **`CommandExecutor`:** Outbound port for executing `command_executor` tool calls through Docker, local process execution, or a fake in tests.
7.  **`OllamaLlmClient`:** OpenAI-compatible Ollama adapter. It supports both buffered chat responses and SSE streaming via `StreamingLlmClient`.

### Hexagonal Package Structure

The repository is organized according to the following hexagonal package layout:

```text
src/main/scala/com/tark/
  domain/       Pure state, memory, and OpenAI-compatible tool protocol values.
  ui/           Portable terminal/frontend action language and typeclasses.
  application/  Provider-neutral backend orchestration and use cases.
  ports/        Frontend-facing and outbound boundary interfaces.
  adapters/     Ollama, Docker/local process, JLine, session, and command implementations.
  bootstrap/    Runtime configuration and composition root wiring.
```

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for dependency rules and the migration map.

Ports are split by direction:

*   `ports.AgentBackend`: the contract consumed by the frontend and implemented by application/backend code.
*   `ports.outbound`: capabilities Tark calls outward, such as `LlmClient`, `StreamingLlmClient`, `CommandExecutor`, `SessionProvider`, and memory summarization. These are ports because they describe what the application needs; concrete Ollama, Docker/local process, filesystem, and JLine implementations live in adapters.
*   `ports.shared`: pure serialization and config contracts reused by application and adapter code.

---

## 🔄 State Machine & Convergence Rules

Tark executes a compact prompt/tool loop:

```
[Prompt] -> [LLMResponse(content, no tool calls)] -> [Persist assistant message]
   |
   v
[LLMResponse(content, tool calls)] -> [CommandExecutor] -> [ToolResult] -> [Prompt with tool messages]
```

To guarantee that the agent converges and does not run indefinitely, Tark enforces a strict tool-depth budget:

1.  **Tool Depth Budget:** Every prompt is limited to 10 tool-response turns. Once reached, Tark halts and returns a depth-limit exceeded warning.
2.  **Model Termination:** The loop stops successfully when the LLM returns content without tool calls; the assistant message is written to chat history and `AgentState`.

---

## 🖥️ Frontend Architecture

The terminal frontend is stream/action based rather than screen-buffer based. The core idea is that frontend vocabulary is portable and pure, while concrete terminal behavior stays inside the JLine adapter.

### Portable UI Layer
The `com.tark.ui` package owns reusable frontend types:

*   `AgentTask[F]`: a unit of frontend work with an optional status description and an `fs2.Stream[F, AgentAction[F]]`.
*   `AgentAction[F]`: UI actions such as `Log`, `SystemMessage`, `ClearScreen`, `AssistantDelta`, `AssistantEnd`, `RequestChoice`, and `Exit`.
*   `TerminalReader[F]`: line and choice input.
*   `TerminalWriter[F]`: notification output, inline assistant streaming output, screen clearing, and flushing.
*   `Spinner`, `TerminalStatus`, `Schedulable`, and `Animatable`: spinner/status abstractions.
*   `TerminalStyle`: a neutral repo-owned style ADT. JLine style classes do not appear in portable UI contracts.

### JLine Adapter
`JLineFrontend` and related classes live under `com.tark.adapters.inbound.terminal.jline`.

Responsibilities:

*   Build and own the terminal/line-reader lifecycle using `Resource`.
*   Read user input with JLine, including Ctrl+D EOF and Ctrl+C cancellation.
*   Render task status descriptions in the status/spinner line.
*   Render complete notifications with `printAbove`.
*   Render streamed assistant content inline using `AssistantDelta` and close it with `AssistantEnd`.
*   Keep command completion, highlighting, and autosuggestions in the JLine adapter.

### Contributing Frontend Enhancements
When improving the frontend:

*   Add portable behavior to `com.tark.ui` only when it is independent of JLine.
*   Keep terminal-specific code in `adapters.inbound.terminal.jline`.
*   Prefer new `AgentAction` values for new UI semantics rather than smuggling meaning through strings.
*   Use `AgentTask.description` for high-level status-bar text and `AgentAction` values for detailed notifications.
*   Stream assistant text with `AssistantDelta`; use `Log` only for complete agent notifications such as tool execution notices.
*   Add focused tests with fake `TerminalReader`, `TerminalWriter`, `Spinner`, and `AgentBackend` implementations. Avoid full interactive terminal golden tests unless there is no smaller seam.

---

## 🔌 Backend Integration

Backend integrations are modeled through outbound ports in `com.tark.ports.outbound`.

### LLM Clients
`LlmClient[F]` provides buffered chat:

```scala
def chat(prompt: Prompt): F[LLMResponse[ToolCall]]
```

`StreamingLlmClient[F]` provides streaming chat:

```scala
def chatStream(prompt: Prompt): Stream[F, LlmStreamEvent]
```

Buffered clients can still participate in the streaming frontend through `StreamingLlmClient.fromBuffered(...)`. `TarkApp` selects a native streaming client when the adapter exposes one, otherwise it uses the buffered fallback.

### Streaming Events
`LlmStreamEvent` models provider-neutral stream chunks:

*   `ContentDelta(text)`: assistant text that should stream to the UI.
*   `ThinkingDelta(text)`: optional reasoning/status content. It is currently not surfaced by default.
*   `ToolCallDelta(...)`: partial tool-call metadata and argument chunks. These are internal and should not be printed directly.
*   `Completed(...)`: stream termination, optionally carrying a complete buffered response.
*   `Failed(message)`: recoverable stream failure signal.

Tool-call deltas are assembled with `ToolCallAccumulator`. Partial JSON arguments remain internal; tools execute only after complete validated `ToolCall` values are produced.

### Ollama Adapter
`OllamaLlmClient` targets Ollama's OpenAI-compatible chat-completions endpoint. It supports:

*   Buffered `chat(...)` using normal JSON responses.
*   Streaming `chatStream(...)` using `stream = Some(true)` and FS2 byte streams.
*   SSE `data:` line parsing into `LlmStreamEvent` values.
*   Fallback behavior through `DefaultAgentBackend` if streaming fails.

### Adding a New Backend
To add a new model provider:

*   Implement `LlmClient[F]` for buffered responses.
*   Implement `StreamingLlmClient[F]` when the provider supports streaming.
*   Expose native streaming by overriding `streaming` on the `LlmClient`.
*   Convert provider-specific stream payloads into `LlmStreamEvent`; do not leak provider JSON into application code.
*   Use `ToolCallAccumulator` or equivalent validation before producing complete `ToolCall` values.
*   Add adapter tests for content chunks, tool-call chunks, malformed chunks, and fallback/error behavior.

---

## 🚀 Developer Quick Start

### Pre-requisites
1.  **Docker:** Ensure the Docker daemon is running locally (`docker ps`).
2.  **Ollama:** Install Ollama and run your model of choice:
    ```bash
    ollama run qwen3-coder:30b
    ```

### Running Tark
Run the interactive CLI using sbt:
```bash
sbt run
```

### Configuration Overrides
Tark can be customized via the following environment variables:
*   `TARK_OLLAMA_URL`: URL to the Ollama endpoint (default: `http://localhost:11434/v1/chat/completions`).
*   `TARK_OLLAMA_MODEL`: Model name to use (default: `qwen3-coder:30b`).
*   `TARK_SANDBOX_IMAGE`: Target docker sandbox image (default: `tark-sandbox:latest`).
*   `TARK_FORCE_BUILD`: Set to `true` to force rebuild the Docker sandbox image on startup (default: `false` - uses fast local image caching).

---

## 🛠️ Contributor Guide

We welcome contributions! Please adhere to the following workspace development standards:

### 1. Adding a Command Tool
Command tools are exposed to the model as OpenAI-compatible `ToolDefinition` values and executed through an outbound port. The current command capability is `command_executor`, implemented by `CommandTool` through the `CommandExecutor[F]` port.

Define the tool schema in `com.tark.domain.tool` terms:
```scala
val myTool = ToolDefinition(
  `type` = "function",
  function = OpenAIFunction(
    name = "custom_tool",
    description = "Describe the tool",
    parameters = OpenAIFunctionParams.Str(description = "JSON arguments")
  )
)
```

Then provide an executor implementation in an adapter package, not in `domain`, `application`, or `ports`:

```scala
given myCommandExecutor[F[_]: Sync]: CommandExecutor[F] with {
  override def definition: ToolDefinition = myTool

  override def execute(context: Context, toolCall: ToolCall): F[ToolResult] =
    Sync[F].blocking {
      // Parse toolCall.function.arguments and run the effectful capability here.
      ToolResult("result returned to the model")
    }
}
```

Register the tool definition when creating the session context. For the default command tool this happens in `DefaultSessionProvider`:

```scala
Context(List(CommandTool.definition), existingMemory, List.empty, Some(sandbox))
```

Keep argument parsing tolerant. Model responses are not guaranteed to be complete or valid, so prefer `Option` decoders and return a clear `ToolResult` such as `Command failed: Tool argument 'command' is missing.` instead of leaking raw decoder failures into the UI.

### 2. Extending Tark with New Capabilities
Use the layer that matches the capability:

*   **New model/backend:** implement `LlmClient[F]`, and implement `StreamingLlmClient[F]` when the provider supports streaming. Keep provider payload parsing inside the adapter.
*   **New tool/capability:** add a `ToolDefinition`, an outbound executor port if the capability is not command-like, and a concrete adapter implementation.
*   **New slash command:** add a branch in `DefaultAgentBackend.processSlashCommand` or extract a command router if the command set becomes large enough to justify it.
*   **New terminal behavior:** add portable UI semantics in `com.tark.ui` and render them in `JLineFrontend`; do not reintroduce screen-buffer state unless the frontend model changes intentionally.
*   **New persistence behavior:** implement or compose a `Sink`/serialization boundary instead of writing files from application code.

Keep dependency direction intact: application code may depend on ports, but not adapters. Concrete technology choices such as Docker, local process execution, STTP, JLine, and filesystem details belong in `adapters` or `bootstrap`.

### 3. Thread Safety Rules (Cats Effect Blocking)
Tark runs on a high-performance Cats Effect work-stealing pool. **Never execute blocking, synchronous I/O or system process builds inside plain `IO { ... }` or `F.delay { ... }` blocks.**
*   Always wrap blocking I/O, process calls, or disk writes in `IO.blocking { ... }` (or `Sync[F].blocking { ... }`).
*   Restoring terminal states or closeable handles must be modeled using `Resource.make`.

### 4. Ports and Focused Tests
When adding or changing a port:
*   Keep provider-specific behavior in adapter packages; ports should describe the protocol boundary, not an Ollama, Docker, or terminal implementation detail.
*   Add focused MUnit coverage around protocol JSON, state transitions, and adapter behavior.

### 5. Hexagonal Architecture Guardrails
We statically enforce package dependency rules using architecture tests. Any changes that violate layer boundaries (e.g., `domain` importing `ports`, `ports` importing `adapters`) will fail the build. Run `sbt "testOnly com.tark.architecture.HexagonalBoundarySpec"` to verify compliance.

---

## 🧪 Testing Reference

### Compiling Code
To compile both production and test files:
```bash
sbt Test/compile
```

### Running Tests
Execute the entire test suite:
```bash
sbt "testOnly *"
```

To run a specific test suite:
```bash
sbt "testOnly com.tark.application.backend.DefaultAgentBackendSpec"
```

---

## 📚 Architecture References

*   [`ARCHITECTURE.md`](ARCHITECTURE.md): target hexagonal package structure, dependency rules, and migration glossary.
*   [`docs/harness-engineering-guide.md`](docs/harness-engineering-guide.md): background notes for the educational harness design.
