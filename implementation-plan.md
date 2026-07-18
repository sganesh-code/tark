# Implementation Plan: TARK-MEMORY-BLOG - Context Management and Memory Layers Reference for Blog

- [ ] **🎟️ TARK-MEMORY-BLOG: Context Management and Memory Layers Reference for Blog**
  - **Description:** 
    This document serves as a comprehensive reference of Tark's multi-layered cognitive memory model and context management system. It maps out the theoretical concepts, actual Scala implementations, and operational lifecycle to act as a structured blueprint for writing a high-quality, Medium-style technical engineering blog post.
    
    ### 🧠 Part 1: The Problem – The "Stateless LLM" & "Context Tax"
    - **The Core Issue:** Raw Large Language Models (LLMs) are completely stateless. Standard chat clients bypass this by feeding the entire raw message backlog back into the prompt on every message.
    - **The Context Tax:** As conversations grow, this raw backlog incurs severe penalties:
      - **Financial Cost:** Exploding token usage.
      - **Performance Latency:** LLMs process larger prompts exponentially slower.
      - **Attention Drift:** Important system instructions, user constraints, and goals get "lost in the middle" of long raw histories.
    - **The Solution (Tark's Approach):** Implement a structured cognitive memory architecture inspired by human psychology. Instead of a monolithic prompt backlog, Tark categorizes and persists information across specialized memory layers, continuously compressing episodic experiences to maintain infinite, lightweight context.

    ### 🗂️ Part 2: Tark’s Multi-Layered Memory Taxonomy
    Tark defines a unified memory model mapped directly to human cognitive systems (defined in `@src/main/scala/com/tark/domain/memory/Memory.scala`):

    1. **Working Memory (`AgentState`)**:
       - *Cognitive Analogue:* Short-term focus / scratchpad.
       - *Role:* Holds active state for the current agent execution cycle.
       - *Structure:* A canonical state engine (`@src/main/scala/com/tark/domain/AgentState.scala`) tracking:
         - **Goal & Deliverable:** What the agent is trying to achieve.
         - **Constraints & Assumptions:** Foundational guardrails.
         - **Known Facts & Open Questions:** Discovered data and missing gaps.
         - **Dynamic Plan:** Chronological steps, tracking the `currentStep` and `completedSteps`.
         - **Tool Results:** Output observations from the execution loop.
         - **Convergence Criteria:** Candidate answers, confidence score, and termination reason (`done`, `reasonForStop`).
       - *How it helps:* It prevents ReAct loops from drifting, looping indefinitely, or repeating failed actions.

    2. **Episodic Memory (`EpisodicMemory` / `EpisodeSummary`)**:
       - *Cognitive Analogue:* Autobiographical memory.
       - *Role:* Distills past sessions into structured takeaways to carry forward across restarts.
       - *Structure:* A list of `EpisodeSummary` case classes containing `sessionId`, `timestamp`, `summary`, and `keyTakeaways` (list of preferences, facts, decisions, or errors).
       - *How it helps:* Prevents the user from repeating themselves across restarts (e.g., repeating preferred visual styles, tool choices, or past bugs solved).

    3. **Procedural Memory (`ProceduralMemory` / `Skill`)**:
       - *Cognitive Analogue:* Muscle memory / Motor skills.
       - *Role:* Captures "how-to" knowledge, such as complex multi-step skills, workflows, and custom tool orchestration patterns.
       - *Structure:* A list of `Skill` models with a `name`, `description`, and list of chronological execution `steps`.
       - *How it helps:* Teaches the agent custom routines or standard operating procedures without bloating the base prompt with massive system instructions.

    4. **Semantic Memory (`SemanticMemory`)**:
       - *Cognitive Analogue:* Generalized factual knowledge.
       - *Role:* Holds static references, domain facts, definitions, and rules about the workspace or project environment.
       - *Structure:* A structured list of factual text entries.

    ### 🔄 Part 3: The Context Lifecycle & Dual-Representation Persistence
    Tark manages memory lifecycle transitions seamlessly across storage and active execution:

    ```
    [On Disk: markdown + JSON footer]
                 │
                 │ 1. Bootstrap (Loads latest session, parses JSON comment)
                 ▼
     [In-Memory: Context (Cats Effect Ref)]
                 │
                 │ 2. ReAct Loop Execution (State changes, tool runs, chat interactions)
                 ▼
     [In-Memory: Updated Context] ◄─── (Dynamic save to disk as dual-representation MD)
                 │
                 │ 3. Session End / Clear (Summarization via secondary LLM call)
                 ▼
     [Episodic Memory Compressed] ───► (Writes back to disk, clearing raw message log)
    ```

    - **Dual-Representation Storage (Markdown + JSON):**
      - Tark serializes the session's active context into a highly readable Markdown file (`target/sessions/session_*.md`) via `@src/main/scala/com/tark/application/instances/ContextInstances.scala`.
      - **Human-Readable Interface:** Humans can inspect or live-edit the goals, plans, constraints, tool results, and interaction histories directly in Markdown.
      - **Machine-Readable Metadata:** An embedded HTML comment block at the very bottom containing `<!-- MEMORY_JSON {"working": ...} -->` allows the system to deserialize the entire typed `Memory` object back into Scala on restart (`@src/main/scala/com/tark/domain/context/Context.scala` -> `Context.deserialize`).
    - **Infinite Context via Compression (Episodic Summarization):**
      - At the end of a session, or when running `/clear` or `/exit`, `@src/main/scala/com/tark/ports/inbound/tool/SessionMemoryTransitions.scala` activates.
      - It triggers the outbound port `EpisodicMemorySummarizer` implemented by `@src/main/scala/com/tark/adapters/backend/ollama/OllamaEpisodicMemorySummarizer.scala`.
      - A secondary, highly focused LLM call is fired using a strict JSON schema prompt (`@src/main/scala/com/tark/ports/outbound/memory/MemoryPrompt.scala`):
        ```json
        {
          "summary": "a concise 2-4 sentence summary of the session goals, actions, and results",
          "takeaways": [
            "takeaway 1, user preference, decision, failure, or fact",
            "takeaway 2, user preference, decision, failure, or fact"
          ]
        }
        ```
      - This newly synthesized summary is appended to the `EpisodicMemory` array.
      - The raw chronological history of conversations/messages is discarded, and the updated episodic memory is written back to the file.
      - **The Result:** When the CLI restarts, the agent is loaded with the summarized lessons of past runs, but with a clean, low-token working window.

    ### 🏗️ Part 4: Hexagonal Architecture Boundary Enforcement
    Tark's architecture enforces strict separation of concerns, keeping cognitive concepts completely pure:
    - **Domain (`com.tark.domain`)**: Pure, dependency-free models. Pure Scala case classes represent `Memory`, `AgentState`, and `Session`. Zero JSON parsing, IO monads, or HTTP client imports.
    - **Ports (`com.tark.ports`)**: Boundaries defining persistence (`Serializable`), summarization contracts (`EpisodicMemorySummarizer`), and state transitions.
    - **Application (`com.tark.application`)**: Orchestrates the ReAct execution, session transitions, and thread-safe in-memory state manipulation using Cats Effect `Ref`.
    - **Adapters (`com.tark.adapters`)**: Concrete technologies. Contains Markdown serialization (`ContextInstances`), Ollama LLM integration (`OllamaEpisodicMemorySummarizer`), and disk/filesystem operations (`DefaultSessionProvider`).
    - **Static Guardrails:** Checked via `HexagonalBoundarySpec` to ensure adapters never leak into the application, and the application never leaks into the domain.

  - **Scope:**
    - **In scope:** Capture progression, definitions, and code structures of:
      - `Memory` and its sub-components (Working, Episodic, Procedural, Semantic).
      - Dual-representation markdown/JSON serialization and bootstrap loading.
      - Episodic summarization and state transitions (`SessionMemoryTransitions`, `OllamaEpisodicMemorySummarizer`, `MemoryPrompt`).
      - Architectural organization of memory layers across Hexagonal boundaries.
    - **Out of scope:** Writing the actual blog post text or publishing it to Medium; modifying codebase files or staging/committing changes.

  - **Implementation Tasks:**
    - [x] **Investigate:** Analyze the memory domain models inside `@src/main/scala/com/tark/domain/memory/Memory.scala` and active execution state in `@src/main/scala/com/tark/domain/AgentState.scala`.
    - [x] **Investigate:** Map how sessions are initialized with previous memory states in `@src/main/scala/com/tark/adapters/context/DefaultSessionProvider.scala`.
    - [x] **Investigate:** Review how context is serialized to Markdown and embedded as JSON in `@src/main/scala/com/tark/application/instances/ContextInstances.scala`.
    - [x] **Investigate:** Study how session history is compressed and persisted through `@src/main/scala/com/tark/ports/inbound/tool/SessionMemoryTransitions.scala` and `@src/main/scala/com/tark/adapters/backend/ollama/OllamaEpisodicMemorySummarizer.scala`.
    - [x] **Verify:** Generate this markdown-based implementation plan in the workspace root to serve as a complete reference for the Medium blog content.
