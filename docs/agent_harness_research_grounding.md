# Research Grounding Notes: Building an LLM Agent Harness

**Purpose:** This document converts the research notes into a reusable Markdown brief for designing, implementing, and evaluating an agent harness built on top of large language models (LLMs).

**Working definition:** An **agent harness** is the runtime layer around an LLM that manages goals, state, planning, tool use, memory, verification, turn-taking, and stopping conditions. The LLM generates reasoning, plans, and actions; the harness controls execution.

---

## 1. Core Thesis

Most practical LLM agents should not be implemented as a free-form chat loop. They should be implemented as a **controlled state machine** with explicit planning, typed tool execution, memory, verification, and convergence rules.

A robust harness should answer four questions at every step:

1. What is the current goal?
2. What is already known?
3. What is the next valid action?
4. When should the conversation stop?

The most important implementation idea is:

> The LLM can propose actions, but the harness should own the loop.

---

## 2. Agent Harness Architecture

A minimal harness can be structured as follows:

```text
User goal
  -> task intake / goal contract
  -> planner
  -> executor loop
       -> tool selection
       -> tool call
       -> observation parsing
       -> state update
  -> verifier / critic
  -> final answer or one blocking clarification
```

Recommended components:

| Component | Responsibility |
|---|---|
| Goal parser | Converts user request into a clear goal, constraints, assumptions, and deliverable. |
| State manager | Maintains the canonical task state instead of relying only on chat history. |
| Planner | Decomposes the task into steps or subgoals. |
| Executor | Performs the next step using generation or tools. |
| Tool registry | Defines callable tools, schemas, permissions, side effects, and validation. |
| Memory layer | Retrieves relevant documents, prior decisions, user preferences, and run summaries. |
| Verifier | Checks whether the answer satisfies the definition of done. |
| Controller | Owns transitions, budgets, stopping rules, retries, and escalation. |
| Trace logger | Records decisions, prompts, tool calls, observations, state diffs, and stop reasons. |

---

## 3. State Object

Do not treat the raw chat transcript as the only source of truth. Maintain a structured state object.

Example:

```json
{
  "goal": "Summarize LLM agent implementation strategies from literature.",
  "deliverable": "Markdown research brief for building an agent harness.",
  "constraints": ["academic tone", "implementation-oriented", "include references"],
  "assumptions": [],
  "known_facts": [],
  "open_questions": [],
  "plan": [],
  "current_step": 0,
  "completed_steps": [],
  "tool_results": [],
  "candidate_answer": null,
  "confidence": 0.0,
  "done": false,
  "reason_for_stop": null
}
```

Why this matters:

- Chat history is noisy and unstructured.
- LLMs can lose track of constraints over long conversations.
- A state object makes the run debuggable and reproducible.
- State diffs make it easier to detect stagnation and repeated actions.

---

## 4. Widely Used Techniques for LLM Agents

### 4.1 Chain-of-Thought and Task Decomposition

**Idea:** Ask the model to break a problem into intermediate reasoning steps before producing an answer.

**Use when:** The task involves multi-step reasoning, planning, arithmetic, diagnosis, or synthesis.

**Harness implication:** Keep private reasoning separate from user-facing output. Store only useful summaries, decisions, and intermediate artifacts in state.

Representative source:

- Wei et al., *Chain-of-Thought Prompting Elicits Reasoning in Large Language Models*, 2022.  
  https://arxiv.org/abs/2201.11903

---

### 4.2 Plan-and-Solve / Plan-then-Execute

**Idea:** First produce a plan, then solve each step. This reduces missing-step errors and makes progress easier to track.

**Use when:** The user goal is broad, ambiguous, or requires several operations.

**Harness implication:** Separate the **planner** from the **executor**. The planner should not execute tools directly; it should produce a step list that the controller can inspect.

Representative source:

- Wang et al., *Plan-and-Solve Prompting: Improving Zero-Shot Chain-of-Thought Reasoning by Large Language Models*, 2023.  
  https://arxiv.org/abs/2305.04091

---

### 4.3 ReAct: Reasoning + Acting

**Idea:** Interleave reasoning steps with external actions and observations.

Typical pattern:

```text
Thought: I need current information.
Action: search(query)
Observation: search results returned.
Thought: The second result is relevant.
Action: open(result)
Observation: source text retrieved.
```

**Use when:** The agent needs tools such as search, APIs, code execution, calculators, databases, browsers, or file systems.

**Harness implication:** ReAct is excellent inside the **execution** state, but should be wrapped by a controller with budgets and stopping rules.

Representative source:

- Yao et al., *ReAct: Synergizing Reasoning and Acting in Language Models*, 2022.  
  https://arxiv.org/abs/2210.03629

---

### 4.4 MRKL and Tool-Routing Systems

**Idea:** Route parts of a task to specialized modules such as calculators, search engines, symbolic solvers, databases, or APIs.

**Use when:** A tool is more reliable than the LLM for a specific operation.

Examples:

- Search for current facts.
- Use a calculator for arithmetic.
- Use a database for internal records.
- Use a compiler or test runner for code.
- Use a retrieval system for documents.

**Harness implication:** Maintain a typed tool registry and validate every tool call.

Representative source:

- Karpas et al., *MRKL Systems: A Modular, Neuro-Symbolic Architecture That Combines Large Language Models, External Knowledge Sources and Discrete Reasoning*, 2022.  
  https://arxiv.org/abs/2205.00445

---

### 4.5 Toolformer-Style Tool Use

**Idea:** Models can learn or be prompted to decide when and how to call external tools.

**Use when:** The agent has access to many tools and must choose the right one based on context.

**Harness implication:** Tool selection should be constrained by schemas, permissions, and side-effect policies. The model should not be allowed to invent tools.

Representative source:

- Schick et al., *Toolformer: Language Models Can Teach Themselves to Use Tools*, 2023.  
  https://arxiv.org/abs/2302.04761

---

### 4.6 Retrieval-Augmented Generation (RAG)

**Idea:** Retrieve relevant documents or facts and condition the LLM response on that retrieved context.

**Use when:** The agent needs factual grounding, private knowledge, long documents, citations, or information that may not be in model weights.

**Harness implication:** Retrieval should update the task state with cited evidence, not just append text to a prompt.

Representative source:

- Lewis et al., *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*, 2020.  
  https://arxiv.org/abs/2005.11401

---

### 4.7 Reflection and Self-Correction

**Idea:** Let the agent critique its own answer or trajectory, then revise.

Common forms:

- Generate answer -> critique -> revise.
- Execute action -> observe error -> update strategy.
- Store feedback in memory for future attempts.

**Use when:** The output can be checked, improved, or tested.

**Harness implication:** Reflection must be bounded. Use one or two verifier passes, not unlimited self-critique.

Representative sources:

- Shinn et al., *Reflexion: Language Agents with Verbal Reinforcement Learning*, 2023.  
  https://arxiv.org/abs/2303.11366
- Madaan et al., *Self-Refine: Iterative Refinement with Self-Feedback*, 2023.  
  https://arxiv.org/abs/2303.17651

---

### 4.8 Tree of Thoughts and Search over Reasoning Paths

**Idea:** Instead of following one reasoning path, explore multiple candidate paths and choose among them.

**Use when:** The task has branching possibilities, backtracking, puzzle-like reasoning, or non-obvious solution paths.

**Harness implication:** Tree search can improve performance but increases cost. Use it selectively for high-value or hard tasks.

Representative source:

- Yao et al., *Tree of Thoughts: Deliberate Problem Solving with Large Language Models*, 2023.  
  https://arxiv.org/abs/2305.10601

---

### 4.9 Memory-Augmented Agents

**Idea:** Give agents memory beyond the current context window.

Useful memory types:

| Memory type | Description |
|---|---|
| Working memory | Current task state, active plan, current constraints. |
| Episodic memory | Prior runs, user preferences, decisions, failures, and summaries. |
| Semantic memory | Documents, papers, knowledge bases, embeddings, and facts. |
| Procedural memory | Skills, tool-use patterns, code snippets, reusable workflows. |

**Harness implication:** Memory retrieval should be relevance-scored and source-aware. Memory should not silently override the user's latest instruction.

Representative sources:

- Park et al., *Generative Agents: Interactive Simulacra of Human Behavior*, 2023.  
  https://arxiv.org/abs/2304.03442
- Wang et al., *Voyager: An Open-Ended Embodied Agent with Large Language Models*, 2023.  
  https://arxiv.org/abs/2305.16291

---

### 4.10 Multi-Agent Collaboration

**Idea:** Use multiple agents with specialized roles, such as planner, researcher, executor, critic, or user proxy.

**Use when:** A task naturally decomposes into roles or benefits from independent review.

**Harness implication:** Multi-agent systems need strong orchestration. Avoid letting agents debate indefinitely.

Recommended pattern:

```text
Planner proposes.
Executor acts.
Critic checks.
Controller decides.
```

Representative sources:

- Wu et al., *AutoGen: Enabling Next-Gen LLM Applications via Multi-Agent Conversation*, 2023.  
  https://arxiv.org/abs/2308.08155
- Li et al., *CAMEL: Communicative Agents for Mind Exploration of Large Language Model Society*, 2023.  
  https://arxiv.org/abs/2303.17760

---

## 5. Conversation Convergence Strategy

The agent should make every turn reduce uncertainty or complete a plan step. If a turn does neither, the harness should stop, ask one targeted question, or re-plan.

### 5.1 Start with a Goal Contract

Convert the user's initial message into:

```text
Goal:
Deliverable:
Constraints:
Known inputs:
Missing inputs:
Definition of done:
```

Example:

```text
Goal: Document strategies for building LLM agent harnesses.
Deliverable: Markdown research brief.
Constraints: Academic, implementation-oriented, literature-grounded.
Known inputs: Prior research notes.
Missing inputs: None blocking.
Definition of done: Covers architecture, techniques, convergence, evaluation, and references.
```

This goal contract prevents drift.

---

### 5.2 Use Explicit Conversation States

Recommended state machine:

```text
INTAKE
  -> CLARIFY, only if required
  -> PLAN
  -> EXECUTE
  -> VERIFY
  -> FINAL
```

Controller loop:

```python
while not state.done:
    if missing_required_input(state):
        return ask_one_blocking_question(state)

    if not state.plan:
        state.plan = planner(state)
        continue

    if next_step_needs_tool(state):
        result = call_tool_safely(state.next_tool_call)
        state = update_state_from_observation(state, result)
        continue

    if plan_complete(state):
        verdict = verifier(state)
        if verdict.passed:
            state.done = True
            state.reason_for_stop = "definition_of_done_satisfied"
        else:
            state = apply_feedback(state, verdict.feedback)
        continue

    state = execute_next_step(state)
```

---

### 5.3 Ask Fewer, Better Clarification Questions

Rule:

```text
Ask a clarification only if the answer would materially change the result.
Otherwise, state an assumption and continue.
```

Good clarification behavior:

```text
I will assume this is for an academic research prototype rather than a production support bot.
```

Poor clarification behavior:

```text
What kind of agent do you want? What tools do you want? What model? What language? What architecture? What benchmarks?
```

The poor version stalls the conversation. The good version moves the task forward while exposing assumptions.

---

### 5.4 Define Hard Stopping Conditions

Every agent loop should have hard limits:

```text
max_steps
max_tool_calls
max_same_tool_retries
max_tokens
max_cost
max_wall_time
max_reflection_rounds
```

And semantic stopping rules:

```text
stop when all plan steps are complete
stop when verifier passes
stop when answer quality exceeds threshold
stop when two drafts are semantically equivalent
stop when the same action repeats
stop when no new facts are added to state
stop when tool results no longer change the decision
```

---

### 5.5 Add a Repetition and Stagnation Detector

Track:

```json
{
  "last_actions": [],
  "last_observations": [],
  "state_fact_count": 0,
  "state_diff_size": 0,
  "semantic_similarity_to_previous_answer": 0.0
}
```

Potential stopping condition:

```python
if repeated_action_count >= 2 and no_new_state_facts:
    stop("stagnation_detected")
```

This prevents the agent from looping through plausible but unproductive actions.

---

### 5.6 Use a Verifier Before Final Output

Verifier checklist:

```text
- Did we answer the user's actual request?
- Did we satisfy all stated constraints?
- Did we include required citations or evidence?
- Did we avoid unresolved blockers?
- Is the final answer concise compared with the accumulated work?
- Did we explain assumptions clearly?
- Did we stop for a valid reason?
```

Recommended verifier output:

```json
{
  "passed": true,
  "missing_requirements": [],
  "issues": [],
  "recommended_revision": null
}
```

---

## 6. Tool Design Pattern

Tools should be defined as typed, validated functions.

Example tool schema:

```json
{
  "name": "search_papers",
  "description": "Find research papers matching a query.",
  "input_schema": {
    "type": "object",
    "properties": {
      "query": {"type": "string"},
      "max_results": {"type": "integer", "minimum": 1, "maximum": 20}
    },
    "required": ["query"]
  },
  "output_schema": {
    "type": "object",
    "properties": {
      "papers": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "title": {"type": "string"},
            "authors": {"type": "array", "items": {"type": "string"}},
            "year": {"type": "integer"},
            "url": {"type": "string"}
          }
        }
      }
    }
  },
  "side_effects": false,
  "requires_user_approval": false,
  "cost_level": "medium"
}
```

Recommended policies:

- Validate inputs before tool execution.
- Validate outputs before adding them to state.
- Mark side-effecting tools explicitly.
- Require approval for tools that send, delete, purchase, publish, or modify external systems.
- Record each tool call in the trace log.
- Do not allow the LLM to invent tool names.

---

## 7. Minimal Agent Harness Pseudocode

```python
MAX_STEPS = 12
MAX_REFLECTION_ROUNDS = 2


def run_agent(user_goal: str):
    state = initialize_state(user_goal)
    trace = []

    for step_idx in range(MAX_STEPS):
        decision = controller_decide(state)

        if decision.type == "ASK_USER":
            return ask_one_question(decision.question)

        if decision.type == "PLAN":
            state.plan = planner(state)

        elif decision.type == "ACT":
            tool_call = executor_select_tool(state)
            result = call_tool_safely(tool_call)
            state = update_state_from_observation(state, result)

        elif decision.type == "DRAFT":
            state.candidate_answer = draft_answer(state)

        elif decision.type == "VERIFY":
            verdict = verifier(state)
            if verdict.passed:
                state.done = True
                state.reason_for_stop = "verifier_passed"
                return finalize(state)
            state = apply_feedback(state, verdict.feedback)

        if should_stop(state, trace):
            return finalize_or_explain_limit(state)

        trace.append(snapshot(state))

    state.reason_for_stop = "max_steps_reached"
    return finalize_or_explain_limit(state)
```

Key property:

> The controller, not the LLM, decides whether the loop continues.

---

## 8. Evaluation Strategy

Evaluate at three levels.

### 8.1 Component-Level Evaluation

Measure whether individual parts work correctly:

| Area | Example metric |
|---|---|
| Tool selection | Percentage of times the correct tool was selected. |
| Tool arguments | Schema-valid calls / total calls. |
| Retrieval | Relevance, citation quality, recall, precision. |
| Planning | Step completeness, ordering quality, missing-step rate. |
| Memory | Helpful retrieval rate, harmful retrieval rate. |
| Verification | False pass rate, false fail rate. |

---

### 8.2 Trajectory-Level Evaluation

Measure the behavior of the whole run:

| Area | Example metric |
|---|---|
| Efficiency | Number of steps, number of tool calls, latency, cost. |
| Stability | Repeated actions, failed retries, re-plans. |
| Convergence | Percentage of runs ending with verifier pass. |
| Stagnation | Turns with no new state facts. |
| Safety | Side-effect attempts requiring approval. |
| Reproducibility | Variance across repeated runs. |

---

### 8.3 Task-Level Evaluation

Measure final task success:

| Area | Example metric |
|---|---|
| Completion | Did the agent satisfy the user's goal? |
| Factuality | Are claims supported by evidence? |
| Usefulness | Human preference or rubric score. |
| Robustness | Performance under ambiguous or adversarial inputs. |
| Generalization | Performance across domains and tools. |

Representative benchmarks:

- Liu et al., *AgentBench: Evaluating LLMs as Agents*, 2023.  
  https://arxiv.org/abs/2308.03688
- Mialon et al., *GAIA: A Benchmark for General AI Assistants*, 2023.  
  https://arxiv.org/abs/2311.12983
- Zhou et al., *WebArena: A Realistic Web Environment for Building Autonomous Agents*, 2023.  
  https://arxiv.org/abs/2307.13854
- Jimenez et al., *SWE-bench: Can Language Models Resolve Real-World GitHub Issues?*, 2023.  
  https://arxiv.org/abs/2310.06770

---

## 9. Recommended Default Harness for Academic Prototyping

```text
Architecture:
State machine + planner/executor/verifier

Execution:
ReAct-style tool loop inside EXECUTE state

Memory:
Short-term state + retrieval over documents + episodic run summaries

Reflection:
One bounded verifier/refinement pass

Convergence:
Goal contract + plan checklist + max-step budget + repeated-action detector + semantic stopping

Logging:
Full trajectory, state diffs, prompts, tool calls, verifier decisions, final stop reason
```

This configuration is simple enough to implement but strong enough for research experiments.

---

## 10. Common Failure Modes and Mitigations

| Failure mode | Cause | Mitigation |
|---|---|---|
| Infinite loops | No stopping criteria or repeated tool calls. | Add max steps, repeated-action detection, and state-diff checks. |
| Goal drift | Agent follows interesting subgoals instead of the user goal. | Maintain a goal contract and verify against it. |
| Tool hallucination | Model invents unavailable tools or invalid arguments. | Use a strict tool registry and schema validation. |
| Over-clarification | Agent asks too many questions and never starts. | Ask only one blocking question when necessary. |
| Under-clarification | Agent proceeds with bad assumptions. | Store assumptions explicitly and expose them in final answer. |
| Retrieval pollution | Irrelevant memory enters context. | Use relevance thresholds and source-aware memory. |
| Unbounded reflection | Agent keeps critiquing without improving. | Limit reflection rounds and detect semantic equivalence. |
| Multi-agent debate | Peer agents argue without resolution. | Use a central controller with authority to stop. |
| Hidden state inconsistency | Chat says one thing, state says another. | Use state as canonical and log state diffs. |

---

## 11. Research Questions Worth Exploring

Potential academic directions:

1. How should an agent decide when to ask a clarification question versus making an assumption?
2. Which state representation best predicts successful convergence?
3. Can semantic state diffs detect stagnation better than step-count limits?
4. How much reflection improves output before returns diminish?
5. How should memory retrieval be evaluated for usefulness versus distraction?
6. Which controller policies reduce cost without hurting task success?
7. Are multi-agent systems actually better than single-agent planner/executor/verifier systems for the same budget?
8. How should harnesses expose uncertainty to users without increasing friction?
9. Can agent traces be compressed into reusable procedural memory?
10. What is the best benchmark design for conversation convergence?

---

## 12. Implementation Checklist

Use this as a build checklist.

### Core Loop

- [ ] Define task state schema.
- [ ] Implement goal contract extraction.
- [ ] Implement planner.
- [ ] Implement executor.
- [ ] Implement verifier.
- [ ] Implement controller transitions.
- [ ] Add max-step and max-tool-call budgets.
- [ ] Add finalization logic.

### Tooling

- [ ] Build typed tool registry.
- [ ] Validate tool inputs.
- [ ] Validate tool outputs.
- [ ] Mark side-effecting tools.
- [ ] Require approval for external side effects.
- [ ] Log all tool calls.

### Memory

- [ ] Add working memory state.
- [ ] Add retrieval over documents or notes.
- [ ] Add episodic summaries.
- [ ] Add memory relevance scoring.
- [ ] Prevent stale memory from overriding current user instructions.

### Convergence

- [ ] Define definition of done.
- [ ] Track completed plan steps.
- [ ] Track repeated actions.
- [ ] Track state diffs.
- [ ] Add semantic similarity check for repeated drafts.
- [ ] Add bounded reflection.
- [ ] Store stop reason.

### Evaluation

- [ ] Log complete trajectories.
- [ ] Score tool-use accuracy.
- [ ] Score final task success.
- [ ] Measure cost and latency.
- [ ] Measure convergence rate.
- [ ] Compare against a baseline chat-only agent.

---

## 13. Reference List

### Surveys and Overviews

- Wang et al., *A Survey on Large Language Model based Autonomous Agents*, 2023.  
  https://arxiv.org/abs/2308.11432
- Xi et al., *The Rise and Potential of Large Language Model Based Agents: A Survey*, 2023.  
  https://arxiv.org/abs/2309.07864
- Weng, *LLM Powered Autonomous Agents*, 2023.  
  https://lilianweng.github.io/posts/2023-06-23-agent/

### Reasoning and Planning

- Wei et al., *Chain-of-Thought Prompting Elicits Reasoning in Large Language Models*, 2022.  
  https://arxiv.org/abs/2201.11903
- Wang et al., *Plan-and-Solve Prompting: Improving Zero-Shot Chain-of-Thought Reasoning by Large Language Models*, 2023.  
  https://arxiv.org/abs/2305.04091
- Yao et al., *Tree of Thoughts: Deliberate Problem Solving with Large Language Models*, 2023.  
  https://arxiv.org/abs/2305.10601

### Tool Use and Agent Execution

- Yao et al., *ReAct: Synergizing Reasoning and Acting in Language Models*, 2022.  
  https://arxiv.org/abs/2210.03629
- Karpas et al., *MRKL Systems: A Modular, Neuro-Symbolic Architecture That Combines Large Language Models, External Knowledge Sources and Discrete Reasoning*, 2022.  
  https://arxiv.org/abs/2205.00445
- Schick et al., *Toolformer: Language Models Can Teach Themselves to Use Tools*, 2023.  
  https://arxiv.org/abs/2302.04761

### Retrieval and Memory

- Lewis et al., *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*, 2020.  
  https://arxiv.org/abs/2005.11401
- Park et al., *Generative Agents: Interactive Simulacra of Human Behavior*, 2023.  
  https://arxiv.org/abs/2304.03442
- Wang et al., *Voyager: An Open-Ended Embodied Agent with Large Language Models*, 2023.  
  https://arxiv.org/abs/2305.16291

### Reflection and Self-Improvement

- Shinn et al., *Reflexion: Language Agents with Verbal Reinforcement Learning*, 2023.  
  https://arxiv.org/abs/2303.11366
- Madaan et al., *Self-Refine: Iterative Refinement with Self-Feedback*, 2023.  
  https://arxiv.org/abs/2303.17651

### Multi-Agent Systems

- Wu et al., *AutoGen: Enabling Next-Gen LLM Applications via Multi-Agent Conversation*, 2023.  
  https://arxiv.org/abs/2308.08155
- Li et al., *CAMEL: Communicative Agents for Mind Exploration of Large Language Model Society*, 2023.  
  https://arxiv.org/abs/2303.17760

### Benchmarks

- Liu et al., *AgentBench: Evaluating LLMs as Agents*, 2023.  
  https://arxiv.org/abs/2308.03688
- Mialon et al., *GAIA: A Benchmark for General AI Assistants*, 2023.  
  https://arxiv.org/abs/2311.12983
- Zhou et al., *WebArena: A Realistic Web Environment for Building Autonomous Agents*, 2023.  
  https://arxiv.org/abs/2307.13854
- Jimenez et al., *SWE-bench: Can Language Models Resolve Real-World GitHub Issues?*, 2023.  
  https://arxiv.org/abs/2310.06770

---

## 14. One-Sentence Design Principle

Build the agent harness as a controlled, inspectable state machine where the LLM proposes reasoning and actions, but the runtime owns state, tools, verification, and stopping.
