# Implementation Plan: Goal Contract Intake & Task Planning
**Target Repository:** tark
**Reference Design:** `@docs/agent_harness_research_grounding.md`

This implementation plan breaks down the development of **Option A: Goal Contract Parser & Structured Intake Phase** and **Option B: Task Planner & Progress Tracker** as a quick follow-up. By executing these tickets, the agent harness will transition from a simple chat loop to a controlled, state-driven execution system grounded by clear contracts and step-by-step progress tracking.

---

- [x] **🎟️ [TARK-INTAKE-001]: Port & Prompt Definitions for Goal Contract Parser**
  - **Description:** Define the outbound port `GoalContractParser` and its prompt/parsing system `GoalContractPrompt` to extract structured goals, deliverables, constraints, assumptions, and known facts from the user's initial input.
  - **Scope:**
    - **In scope:**
      - A new domain representation `GoalContract` or leveraging the existing `AgentState` fields directly.
      - Outbound port `GoalContractParser[F[_]]` with `def parseGoal(input: String): F[GoalContract]`.
      - System instruction and parsing utility `GoalContractPrompt` using Circe for structured JSON parsing and falling back to a line-by-line parser on format errors (modeled after `@src/main/scala/com/tark/ports/outbound/memory/MemoryPrompt.scala`).
    - **Out of scope:**
      - Writing concrete Ollama client calls (deferred to `TARK-INTAKE-002`).
      - Integrating into the main backend input flow (deferred to `TARK-INTAKE-003`).
  - **Implementation Tasks:**
    - [x] Create the case class `GoalContract` (fields: `goal: String`, `deliverable: String`, `constraints: List[String]`, `assumptions: List[String]`, `knownFacts: List[String]`) in `src/main/scala/com/tark/ports/outbound/backend/GoalContractParser.scala` or directly in `@src/main/scala/com/tark/domain/AgentState.scala`.
      - *Created `GoalContract.scala` domain class in `com.tark.domain` package with automatic JSON codecs. Added `withGoalContract` helper to `AgentState`.*
    - [x] Define the interface trait `GoalContractParser` in `src/main/scala/com/tark/ports/outbound/backend/GoalContractParser.scala`.
      - *Defined outbound port interface `GoalContractParser` representing the goal contract extraction.*
    - [x] Create `src/main/scala/com/tark/ports/outbound/backend/GoalContractPrompt.scala` defining the LLM system instructions to produce a strict JSON output matching the `GoalContract` schema.
      - *Created `GoalContractPrompt.scala` defining instructions, template user prompts, and structured JSON targets.*
    - [x] Implement `GoalContractPrompt.parseGoalResponse(content: String): GoalContract` with JSON parsing via Circe and a robust fallback line-by-line parser.
      - *Implemented pure `Deserializable[String, GoalContract]` typeclass instance under `GoalContractPrompt` supporting Circe and fallback parsing.*
    - [x] Add unit tests in `src/test/scala/com/tark/ports/outbound/backend/GoalContractPromptSpec.scala` testing JSON parsing success and fallback resilience.
      - *Created and verified `GoalContractPromptSpec.scala` containing exhaustive test scenarios.*

- [x] **🎟️ [TARK-INTAKE-002]: Concrete Ollama Adapter for Goal Contract Parser**
  - **Description:** Create the concrete adapter `OllamaGoalContractParser` that uses the standard LLM client to request and parse the goal contract from Ollama.
  - **Scope:**
    - **In scope:**
      - Concrete class `OllamaGoalContractParser[F[_]: Sync]` implementing `GoalContractParser[F]`.
      - Chat prompting using `LlmClient` and parsing the text output via `GoalContractPrompt`.
    - **Out of scope:**
      - Integrating into `DefaultAgentBackend`.
  - **Implementation Tasks:**
    - [x] Implement the adapter `OllamaGoalContractParser` in `src/main/scala/com/tark/adapters/backend/ollama/OllamaGoalContractParser.scala` injecting `@src/main/scala/com/tark/ports/outbound/backend/LlmClient.scala`.
      - *Implemented the concrete `OllamaGoalContractParser` adapter utilizing the generic `LlmClient` and resolving the `Deserializable` typeclass via given imports.*
    - [x] Add an integration spec `src/test/scala/com/tark/adapters/backend/ollama/OllamaGoalContractParserSpec.scala` using a Fake `LlmClient` to assert that correct messages are formulated and responses are correctly routed and parsed.
      - *Created `OllamaGoalContractParserSpec.scala` testing successful model prompts, response extraction, and fallback parsing under malformed text within an IO context.*

- [x] **🎟️ [TARK-INTAKE-003]: Integrate Goal Intake Phase into DefaultAgentBackend**
  - **Description:** Wire the Goal Contract Parser into the backend input handler. On the first user message of a session, run the parser to extract the `GoalContract`, update the `AgentState` in the context's working memory, and print a system message outlining the contract before launching the ReAct loop.
  - **Scope:**
    - **In scope:**
      - Modifying `DefaultAgentBackend` to check if `session.context.memory.working` already has an active goal.
      - If no goal is present, execute the `GoalContractParser` on the user input, enrich the working `AgentState`, notify the user with a formatted terminal message, and run the ReAct engine with the enriched context.
      - Updating bootstrap wiring and tests.
    - **Out of scope:**
      - Generating a step-by-step sequential plan list (deferred to `TARK-PLAN-001`).
  - **Implementation Tasks:**
    - [x] Add `GoalContractParser[F]` dependency to `@src/main/scala/com/tark/application/backend/DefaultAgentBackend.scala`.
      - *Added `GoalContractParser` driving dependency via implicit resolution.*
    - [x] Modify `DefaultAgentBackend.processPrompt` to check if the session's active `AgentState` contains a goal. If it is empty, call the parser first.
      - *Intercepted initial inputs to execute `GoalContractParser` when no active goal is found in `AgentState`.*
    - [x] Populate the `AgentState` with the parsed contract's goal, deliverable, constraints, assumptions, and known facts using the helpers in `@src/main/scala/com/tark/domain/AgentState.scala`.
      - *Updated working memory state dynamically with the parsed goal contract.*
    - [x] Emit a clean system message action in the backend task stream to inform the user (e.g., `[Intake] Active Goal: <goal>`, `[Intake] Constraints: <constraints>`).
      - *Formulated and streamed informative system log notifications for established goals and constraints.*
    - [x] Update wiring in `@src/main/scala/com/tark/bootstrap/TarkApp.scala` and `OllamaRuntime.scala` to construct and supply the `OllamaGoalContractParser`.
      - *Registered `given goalContractParser` inside `OllamaRuntime.scala` to enable automatic dependency injection.*
    - [x] Update `@src/test/scala/com/tark/application/backend/DefaultAgentBackendSpec.scala` to mock/fake the parser and verify that a new session successfully extracts and stores the goal contract in context before running the conversation.
      - *Added a comprehensive intake integration test. Isolated all other non-intake tests by initializing them with a pre-populated goal to bypass intake cleanly.*

- [ ] **🎟️ [TARK-PLAN-001]: Port & Prompt Definitions for Task Planner**
  - **Description:** Define the `TaskPlanner` outbound port and prompt system to decompose the active goal/deliverables from the `AgentState` into a sequential sequence of execution steps.
  - **Scope:**
    - **In scope:**
      - Interface `TaskPlanner[F[_]]` with `def generatePlan(goal: String, deliverable: String, constraints: List[String]): F[List[String]]`.
      - System instruction and parsing utility `TaskPlannerPrompt` instructing the LLM to output a JSON list of strings (or fallback newline/bullet splitter).
    - **Out of scope:**
      - Integrating into the backend execution flow.
  - **Implementation Tasks:**
    - [ ] Define the interface trait `TaskPlanner` in `src/main/scala/com/tark/ports/outbound/backend/TaskPlanner.scala`.
    - [ ] Create `src/main/scala/com/tark/ports/outbound/backend/TaskPlannerPrompt.scala` specifying LLM instructions to generate a sequential JSON array of steps based on goals and constraints.
    - [ ] Add unit tests in `src/test/scala/com/tark/ports/outbound/backend/TaskPlannerPromptSpec.scala` to verify plan extraction on JSON and line-delimited outputs.

- [ ] **🎟️ [TARK-PLAN-002]: Ollama Adapter & Backend Integration for Task Planner**
  - **Description:** Implement `OllamaTaskPlanner` and integrate it into the `DefaultAgentBackend` to generate a checklist plan immediately after goal contract extraction. Update the UI to render the progress checklist.
  - **Scope:**
    - **In scope:**
      - Concrete class `OllamaTaskPlanner[F[_]: Sync]` in `src/main/scala/com/tark/adapters/backend/ollama/OllamaTaskPlanner.scala`.
      - Updating the intake phase in `DefaultAgentBackend` to run the planner immediately after extracting the goal contract, updating `AgentState.plan` and `currentStep` to 0.
      - Displaying the initial plan steps in the console.
    - **Out of scope:**
      - Complete state machine transition refactoring (e.g. `CLARIFY` or `VERIFY` states).
  - **Implementation Tasks:**
    - [ ] Create `src/main/scala/com/tark/adapters/backend/ollama/OllamaTaskPlanner.scala` utilizing `LlmClient[F]` and `TaskPlannerPrompt`.
    - [ ] Add `TaskPlanner[F]` as a dependency in `@src/main/scala/com/tark/application/backend/DefaultAgentBackend.scala`.
    - [ ] Update `DefaultAgentBackend.processPrompt` to run `planner.generatePlan` immediately after goal extraction, saving the resulting step list into `AgentState` via `withPlan`.
    - [ ] Update `@src/main/scala/com/tark/bootstrap/TarkApp.scala` and `OllamaRuntime.scala` to instantiate and inject `OllamaTaskPlanner`.
    - [ ] Assert in `@src/test/scala/com/tark/application/backend/DefaultAgentBackendSpec.scala` that plans are generated, saved, and successfully injected into downstream prompt system instructions automatically by `@src/main/scala/com/tark/ports/outbound/backend/ContextProvider.scala`.
    - [ ] Run the complete testing and static hexagonal boundary validation suite (`sbt test`) to verify 100% compliance.
