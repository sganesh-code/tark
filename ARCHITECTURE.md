# Tark Architecture

Tark is being organized around hexagonal architecture: domain values and
application use cases sit at the center, ports describe boundaries, adapters
connect external technology, and bootstrap code composes a runnable program.

## Planned Package Map

| Package | Role | Current migration source |
| --- | --- | --- |
| `com.tark.domain` | Core domain values, state, tool descriptions, memory values, UI value objects, and ReAct state values. | `@src/main/scala/com/tark/domain` |
| `com.tark.application` | Provider-neutral use cases, orchestration services, transition helpers, and default domain typeclass instances. | Provider-neutral logic now mixed across `@src/main/scala/com/tark/ports`, `@src/main/scala/com/tark/adapters/backend/ollama`, and model companions. |
| `com.tark.ports.inbound` | Driving ports invoked by the CLI or other entrypoints, such as chat input, slash command dispatch, and keyboard handling. | `@src/main/scala/com/tark/ports/inbound` |
| `com.tark.ports.outbound` | Driven ports implemented by infrastructure, such as LLM clients, sandbox execution, session persistence, trace writing, and memory summarization. | `@src/main/scala/com/tark/ports/outbound` |
| `com.tark.adapters` | Concrete technology implementations for Ollama/STTP, Docker/local process execution, JLine terminal UI, command tools, and filesystem/session persistence. | `@src/main/scala/com/tark/adapters` plus concrete implementations currently embedded in ports. |
| `com.tark.bootstrap` | Runtime configuration loading and composition root wiring for the CLI application. | `@src/main/scala/com/tark/bootstrap` |

## Migration Glossary

- **Domain:** Pure project language and state. It should be readable without
  understanding Cats Effect, STTP, Docker, JLine, or Ollama.
- **Application:** The provider-neutral behavior that makes Tark a controlled
  agent harness: command routing, ReAct execution, state transitions, tracing
  decisions, and persistence decisions.
- **Inbound port:** An API the outside world calls to drive Tark.
- **Outbound port:** An API Tark calls to reach an external capability.
- **Adapter:** A concrete implementation of a port using a specific library,
  process, protocol, filesystem, terminal, or external service.
- **Bootstrap:** The edge of the program that reads runtime configuration and
  chooses concrete adapters.

## Dependency Rules

The dependency direction should point inward:

```text
bootstrap -> adapters -> ports -> application -> domain
bootstrap -> application
application -> ports
```

These rules are the source of truth during the cleanup:

1. `com.tark.domain` must not import `com.tark.ports`, `com.tark.adapters`,
   `com.tark.application`, or `com.tark.bootstrap`.
2. `com.tark.application` may import domain values and port interfaces, but it
   must not import concrete adapters such as Ollama, Docker, JLine, STTP, or
   `scala.sys.process`.
3. `com.tark.ports` may import domain/value types and shared typeclass shapes,
   but it must not instantiate adapters or read runtime configuration.
4. `com.tark.adapters` may import ports and domain values to implement concrete
   behavior. Adapters should not become the owner of provider-neutral use-case
   orchestration.
5. `com.tark.bootstrap` is allowed to know about every layer because it is the
   composition root. Runtime configuration, default givens, and concrete client
   selection belong here or in adapter-owned factories called from here.
6. Tests should mirror these boundaries: domain tests use no adapters, port law
   tests use fakes, application tests use fake ports, and adapter tests exercise
   concrete technology integrations.

## Expected Package Shape

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
    react/
    tool/
  ports/
    inbound/
      chat/
      command/
      ui/
    outbound/
      backend/
      context/
      memory/
      sandbox/
      trace/
    shared/
      algebra/
      rendering/
      serialization/
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

## Current Boundary Violations to Fix

The current package names partially describe a hexagonal architecture, but a few
files cross boundaries in ways that make the structure hard to teach and evolve.
These are the first cleanup targets:

| File | Current issue | Target owner |
| --- | --- | --- |
| `@src/main/scala/com/tark/ports/outbound/backend/BackendProvider.scala` | Resolved in TICKET-005: the port package contains only the `BackendProvider` contract; Ollama/STTP client construction now lives in `com.tark.bootstrap.OllamaRuntime`. | Keep provider-specific runtime wiring in `com.tark.bootstrap` or adapter-owned factories. |
| `@src/main/scala/com/tark/ports/inbound/tool/InputProcessor.scala` | Resolved in TICKET-004: the inbound port now contains only the use-case contract, while default chat orchestration lives in `com.tark.application.chat.DefaultInputProcessor` and the provider-neutral ReAct loop lives in `com.tark.application.react.DefaultReActExecutor`. | Keep application orchestration in `com.tark.application`; keep concrete provider protocols in adapters. |
| `@src/main/scala/com/tark/ports/outbound/memory/EpisodicMemorySummarizer.scala` | Resolved in TICKET-005: the outbound memory port contains only the summarizer contract; the default Ollama-backed summarizer is wired in `com.tark.bootstrap.OllamaRuntime`. | Keep the trait as an outbound port and runtime construction in bootstrap. |
| `@src/main/scala/com/tark/domain/context/Context.scala` | Resolved in TICKET-002: domain `Context` no longer imports ports or defines `ContextOps`, `ToolRegistry`, or `Serializable` instances in its companion. | Keep typeclass instances in `com.tark.application.instances`; revisit whether `Context.sandbox` belongs in domain in a later model cleanup. |
| `@src/main/scala/com/tark/ports/outbound/sandbox/Sandbox.scala` | Resolved in TICKET-006: the concrete local process sandbox moved to `com.tark.adapters.sandbox.local`, Docker sandbox code lives under `com.tark.adapters.sandbox.docker`, and the port package contains only `SandboxManager`. | Keep only sandbox boundaries in ports and concrete process/container implementations in adapters. |

Tests now mirror the package boundaries more closely: domain suites cover domain
values, application suites cover use-case orchestration, port suites cover port
laws and pure shared helpers, adapter suites cover concrete technologies, and
shared test fakes live under `@src/test/scala/com/tark/support`.
