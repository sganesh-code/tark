# Tark: An Educational LLM Agent Harness

Tark is an academic reference playground and learning implementation for **LLM Harness Engineering** built in **Scala 3** using functional programming principles with **Cats Effect**, **FS2**, and **Sttp**.

Rather than a closed or complex production framework, Tark is designed as a transparent, highly accessible reference model. Its core purpose is to help developers, students, and AI researchers learn how to build, control, and inspect AI agent loops from scratch. Based on modern software agent research, Tark acts as a controlled state machine: the LLM proposes reasoning and actions, but the Scala runtime retains complete control over state, tools, validation, verification, and safety boundaries.

---

## 📖 Table of Contents
1. [High-Level Concepts](#-high-level-concepts)
2. [System Architecture](#-architecture--component-diagram)
3. [State Machine & Convergence Rules](#-state-machine--convergence-rules)
4. [Developer Quick Start](#-developer-quick-start)
5. [Contributor Guide](#-contributor-guide)
6. [Testing Reference](#-testing-reference)
7. [Architecture References](#-architecture-references)

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
                          |  DefaultInputProcessor  |  <---+ Writes Sessions
                          +------------+------------+      |
                                       |                   |
                     +-----------------+-----------------+ |
                     |                                   v |
                     v                           +-------+-------+
        +------------+------------+              |  Session/Sink |
        | Prompt / LLMResponse    |              +---------------+
        | ToolCall / ToolResult   |
        +------------+------------+
                     |
         +-----------+-----------+
         |                       |
         v                       v
+--------+--------+     +--------+--------+
| CommandExecutor |     | OllamaLlmClient |
+-----------------+     +-----------------+
  (Docker/local shell)    (Local LLM model)
```

### Component Breakdown
1.  **`TarkCLI` / `JLineFrontend`:** Manages the terminal rendering layer, keystrokes, scroll offsets, and visual styling.
2.  **`InputProcessor` / `DefaultInputProcessor`:** The inbound port defines chat input processing; the default application service routes slash commands and runs the Blog-style prompt/tool loop.
3.  **`Prompt` / `LLMResponse` / `ToolCall` / `ToolResult`:** The core LLM protocol keeps assistant text and native tool calls together.
4.  **`CommandExecutor`:** Outbound port for executing `command_executor` tool calls through Docker, local process execution, or a fake in tests.
5.  **`OllamaLlmClient`:** Interfaces with the local Ollama OpenAI-compatible chat endpoint.

### Hexagonal Package Structure

The repository is organized according to the following hexagonal package layout:

```text
src/main/scala/com/tark/
  domain/       Pure state, memory, tool protocol, and UI value objects.
  application/  Provider-neutral use cases and orchestration.
  ports/        Inbound, outbound, and shared boundary interfaces.
  adapters/     Ollama, Docker/local process, JLine, session, and command implementations.
  bootstrap/    Runtime configuration and composition root wiring.
```

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for dependency rules and the migration map.

Ports are split by direction:

*   `ports.inbound`: APIs that drive Tark from outside, such as chat input, slash commands, and keyboard handling.
*   `ports.outbound`: capabilities Tark calls outward, such as `LlmClient`, `CommandExecutor`, `SessionProvider`, `Frontend`, and screen writing. These are ports because they describe what the application needs; the concrete Ollama, Docker/local process, filesystem, and JLine implementations live in adapters.
*   `ports.shared`: pure serialization and UI contracts reused by application and adapter code.

---

## 🔄 State Machine & Convergence Rules

Tark executes a compact Blog-style tool loop:

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

### 1. Adding a Custom Tool
To add a tool, define its OpenAI-compatible `ToolDefinition` and provide an outbound executor port implementation:
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

### 2. Thread Safety Rules (Cats Effect Blocking)
Tark runs on a high-performance Cats Effect work-stealing pool. **Never execute blocking, synchronous I/O or system process builds inside plain `IO { ... }` or `F.delay { ... }` blocks.**
*   Always wrap blocking I/O, process calls, or disk writes in `IO.blocking { ... }` (or `Sync[F].blocking { ... }`).
*   Restoring terminal states or closeable handles must be modeled using `Resource.make`.

### 3. Ports and Focused Tests
When adding or changing a port:
*   Keep provider-specific behavior in adapter packages; ports should describe the protocol boundary, not an Ollama, Docker, or terminal implementation detail.
*   Add focused MUnit coverage around protocol JSON, state transitions, and adapter behavior.

### 4. Hexagonal Architecture Guardrails
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
sbt "testOnly com.tark.application.chat.DefaultInputProcessorSpec"
```

---

## 📚 Architecture References

*   [`ARCHITECTURE.md`](ARCHITECTURE.md): target hexagonal package structure, dependency rules, and migration glossary.
*   [`docs/harness-engineering-guide.md`](docs/harness-engineering-guide.md): background notes for the educational harness design.
