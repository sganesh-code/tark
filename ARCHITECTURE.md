# Tark Architecture

Tark is organized around a strict Hexagonal Architecture: domain values and application use cases sit at the center, ports describe boundaries, adapters connect external technology, and bootstrap code composes a runnable program.

## Package Map

| Package | Role |
| --- | --- |
| `com.tark.domain` | Core domain values, OpenAI-compatible tool protocol values, memory values, and UI value objects. |
| `com.tark.application` | Provider-neutral use cases, chat orchestration, transition helpers, and serialization/context instances. |
| `com.tark.ports.inbound` | Driving ports invoked by the CLI or other entrypoints, such as chat input, slash command dispatch, and keyboard handling. |
| `com.tark.ports.outbound` | Driven ports implemented by infrastructure, such as LLM clients, command execution, session persistence, and memory summarization. |
| `com.tark.adapters` | Concrete technology implementations for Ollama/STTP, Docker/local process execution, JLine terminal UI, command tools, and filesystem/session persistence. |
| `com.tark.bootstrap` | Runtime configuration loading and composition root wiring for the CLI application. |

## Architecture Glossary

- **Domain:** Pure project language and state. It must be readable without understanding Cats Effect, STTP, Docker, JLine, or Ollama.
- **Application:** The provider-neutral behavior that makes Tark a controlled agent harness: command routing, prompt/tool execution, state transitions, and persistence decisions.
- **Inbound port:** An API the outside world calls to drive Tark.
- **Outbound port:** An API Tark calls to reach an external capability.
- **Adapter:** A concrete implementation of a port using a specific library, process, protocol, filesystem, terminal, or external service.
- **Bootstrap:** The edge of the program that reads runtime configuration and chooses concrete adapters.

## Dependency Rules

The dependency direction must always point inward:

```text
bootstrap -> adapters -> ports -> application -> domain
bootstrap -> application
application -> ports
```

1. `com.tark.domain` must not import `com.tark.ports`, `com.tark.adapters`, `com.tark.application`, or `com.tark.bootstrap`.
2. `com.tark.application` may import domain values and port interfaces, but it must not import concrete adapters such as Ollama, Docker, JLine, STTP, or `scala.sys.process`.
3. `com.tark.ports` may import domain/value types and shared typeclass shapes, but it must not instantiate adapters or read runtime configuration.
4. `com.tark.adapters` may import ports and domain values to implement concrete behavior. Adapters should not become the owner of provider-neutral use-case orchestration.
5. `com.tark.bootstrap` is allowed to know about every layer because it is the composition root. Runtime configuration, default givens, and concrete client selection belong here or in adapter-owned factories called from here.

## Package Shape

```text
src/main/scala/com/tark/
  domain/
    context/
    memory/
    react/
    tool/
    ui/
  application/
    chat/
    instances/
    memory/
  ports/
    inbound/
      chat/
      command/
      ui/
    outbound/
      backend/
      context/
      memory/
      tool/
      ui/
    shared/
      config/
      serialization/
      ui/
  adapters/
    inbound/
      terminal/jline/
    backend/ollama/
    context/
    sandbox/docker/
    sandbox/local/
    tool/command/
    ui/
  bootstrap/
```

## Rules Governing Code Quality

To ensure Tark remains an exceptional educational platform and reference implementation, every contribution must adhere to the following rules:

### 1. Architectural Guardrails (Hexagonal Boundary Check)
- Dependency rules are statically validated on every test run.
- No layer bypasses are allowed. No package from an inner layer may import from an outer layer.
- Run `sbt "testOnly com.tark.architecture.HexagonalBoundarySpec"` to verify layer integrity.

### 2. Pure Functional Programming Principles
- All state changes, transitions, and user inputs must be modeled as pure values.
- Immutability is the default. Do not use mutable variables (`var`), mutable collections, or side-effecting state outside local, isolated performance hotspots.
- Side effects must be explicitly captured and managed using the Cats Effect type system (`IO` or `F`).

### 3. Non-Blocking I/O (Thread Safety)
- Cats Effect manages a cooperatively scheduled, non-blocking work-stealing thread pool. Any blocking synchronous operations will starve this pool.
- **Never** execute blocking, synchronous I/O, filesystem operations, process spawns, or socket reads/writes inside standard `IO { ... }` or `F.delay { ... }` blocks.
- **Always** wrap synchronous or blocking work in `IO.blocking { ... }` or `Sync[F].blocking { ... }` to safely dispatch them to a dedicated blocking execution context.
- System resources (such as file handles, terminal states, process handles, and docker containers) must be safely managed using the `Resource` or `Resource.make` lifecycle patterns to prevent leaks on failure or termination.

### 4. Direct Composition and Fakes over Mocking
- Avoid mock frameworks (like Mockito or ScalaMock) which weaken typing and produce fragile tests.
- For outbound ports in tests, write explicit, simple, and strongly typed fake implementations in the relevant spec. Fakes should record calls and provide programmable static responses when behavior verification matters.

### 5. Focused Deterministic Testing
- Protocol values, serialization, UI formatting, and adapter behavior must be covered by deterministic tests.
- Prefer small direct tests over broad compatibility law suites while the project is still early-stage.
