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
    *   **Working Memory (`AgentState`):** Fast, in-memory scratchpad tracking the current goal, deliverable, open questions, assumptions, and tool results.
    *   **Episodic Memory:** Stores summaries of prior sessions to resume context across chat startups.
    *   **Procedural Memory:** Holds registered recipes and skills.
    *   **Semantic Memory:** Handles document reference indexes and facts.
*   **Execution Sandbox:** All tools run in an isolated environment (such as a Docker container) rather than directly on the developer's host machine.
*   **Trajectory Tracing:** Every ReAct plan and action is recorded into a beautifully formatted Markdown trace document (`react-trace-*.md`) next to the session record for full audibility.

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
                          +------------+------------+      | & Markdown Traces
                                       |                   |
                     +-----------------+-----------------+ |
                     |                                   v |
                     v                           +-------+-------+
        +------------+------------+              |  Session/Sink |
        |  DefaultReActExecutor   |              +---------------+
        +------------+------------+
                     |
         +-----------+-----------+
         |                       |
         v                       v
+--------+--------+     +--------+--------+
|  DockerSandbox  |     | OllamaLlmClient |
+-----------------+     +-----------------+
  (Runs host tools)       (Local LLM model)
```

### Component Breakdown
1.  **`TarkCLI` / `JLineFrontend`:** Manages the terminal rendering layer, keystrokes, scroll offsets, and visual styling.
2.  **`InputProcessor` / `DefaultInputProcessor`:** The inbound port defines chat input processing; the default application service orchestrates slash commands vs. agent prompts and manages session and trace writes.
3.  **`DefaultReActExecutor`:** Executes the provider-neutral ReAct planning-action loop step-by-step.
4.  **`DockerSandbox`:** Manages a lightweight Alpine Linux Docker container to run commands safely.
5.  **`OllamaReActLlmClient` / `OllamaLlmClient`:** Interfaces with local Ollama endpoints using structured JSON schemas to prompt models.

### Hexagonal Package Structure

The repository is organized according to the following hexagonal package layout:

```text
src/main/scala/com/tark/
  domain/       Pure state, memory, tool, ReAct, and UI value objects.
  application/  Provider-neutral use cases and orchestration.
  ports/        Inbound, outbound, and shared boundary interfaces.
  adapters/     Ollama, Docker/local process, JLine, session, and command implementations.
  bootstrap/    Runtime configuration and composition root wiring.
```

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for dependency rules and the migration map.

Ports are split by direction:

*   `ports.inbound`: APIs that drive Tark from outside, such as chat input, slash commands, and keyboard handling.
*   `ports.outbound`: capabilities Tark calls outward, such as `LlmClient`, `SandboxManager`, `SessionProvider`, `TraceWriter`, `Frontend`, and screen writing. These are ports because they describe what the application needs; the concrete Ollama, Docker/local process, filesystem, and JLine implementations live in adapters.
*   `ports.shared`: pure algebras, serialization, rendering, tool, and ReAct helper contracts that are reused by both application and adapter code.

---

## 🔄 State Machine & Convergence Rules

Tark executes an iterative ReAct (Reasoning and Acting) loop to solve complex problems:

```
[Prompt] -> (Reasoning/Thought) -> [Action/Tool selection] -> [Sandbox Execution] -> (Observation) -> [Verify/Done?]
```

To guarantee that the agent converges and does not run indefinitely, Tark enforces **Three Strict Convergence Safety Rules**:

1.  **Step Budget (Maximum Depth):** Every prompt is limited to `maxSteps` (defaults to `10`). Once reached, Tark halts and returns a depth-limit exceeded warning.
2.  **Stagnation Detection:** If the LLM invokes the exact same tool with the exact same arguments sequentially, the loop detects a stagnation block and terminates to save API tokens.
3.  **Verification Termination:** The execution loop only terminates successfully when the LLM emits a valid `conclude_task` or `Finish` call.

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
To add a tool, implement the `Tool` case class and register it in the `ToolRegistry`:
```scala
val myTool = Tool(
  name = "custom_tool",
  execute = (args: Map[String, String]) => {
    // Tool business logic
    "Output to return to LLM"
  }
)
```

### 2. Thread Safety Rules (Cats Effect Blocking)
Tark runs on a high-performance Cats Effect work-stealing pool. **Never execute blocking, synchronous I/O or system process builds inside plain `IO { ... }` or `F.delay { ... }` blocks.**
*   Always wrap blocking I/O, process calls, or disk writes in `IO.blocking { ... }` (or `Sync[F].blocking { ... }`).
*   Restoring terminal states or closeable handles must be modeled using `Resource.make`.

### 3. Port Algebra and Law Tests
Tark ports are documented in [`docs/port-algebra.md`](docs/port-algebra.md), with consolidation decisions in [`docs/adr-port-algebra-consolidation.md`](docs/adr-port-algebra-consolidation.md).

When adding or changing a port:
*   Add the port to the algebra inventory with its law sketch and instance locations.
*   Prefer the existing shared shapes (`Updatable`, `Appendable`, `Registry`, `Dispatcher`) only when they preserve the domain contract without weakening types.
*   Keep provider-specific behavior in adapter packages; ports should describe the protocol boundary, not an Ollama, Docker, or terminal implementation detail.
*   Add deterministic MUnit law coverage using `PortLawChecks` or a focused spec before refactoring behavior.

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
*   [`docs/port-algebra.md`](docs/port-algebra.md): inventory of ports, algebra families, laws, and instance locations.
*   [`docs/adr-port-algebra-consolidation.md`](docs/adr-port-algebra-consolidation.md): accepted decisions on which abstractions stay domain-specific and which can be shared.
