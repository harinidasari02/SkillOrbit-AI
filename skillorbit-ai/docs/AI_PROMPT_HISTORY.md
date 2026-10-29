# AI use disclosure and prompt history

## Honest summary
* **Claude (claude.ai)** wrote most of the code in this repository in a single working session: the Java generator, rule engine,
  validator, metrics, exporter, Spring controllers/services, UI, tests and documents. It also reviewed the earlier
  versions of this project and wrote the problem list that motivated this rewrite.
* **Ollama (`llama3.2:3b`)** is used *at runtime only* to phrase an explanation of a decision that the rule engine has already made.
  It is never used to decide eligibility, budgets or labels, and the application works without it.
* **What the project owner must still do before submitting:** read and be able to explain the code (the challenge says you will be asked to
  walk through it), run `./mvnw test` and the app once, and **add any prompts you sent to other tools** (e.g. ChatGPT, Google AI Studio) in the log below.
  I cannot see those conversations and have not invented them.

## What Claude verified vs. what it relied on
* Verified by running: generator, validator (48 checks), self-test (rule unit tests + validator mutation tests), reproducibility (twice, byte-identical), 9 JUnit test bodies via a stub runner, and controller logic against generated data.
* Not verified: Maven build, Spring context start-up, real Ollama responses, loading `sql/seed_data.sql` into MySQL.
* Design decisions Claude made and the owner should confirm they agree with: removing the JPA/MySQL layer, a fixed as-of date of 2026-10-28, seed 20261022, edge-case counts (3 starter / 25 full).

## Prompt log: Claude (claude.ai), 2026-10-29
Prompts typed by the project owner in the working conversation (attachments: the challenge brief, FAQ and submission-guidelines PDFs, then `SkillOrbit-core-fixes.zip`, later `SkillOrbit_AI_Merged_Review.zip`):

1. `check what are the issues means tell me`
2. `you do it`
3. `why python in java only do it`
4. `i said java full stack and ollama as an ai`
5. `now check this`
6. `then give the correct version`

## Prompt log: other tools
> TODO (owner): paste every prompt sent to ChatGPT / other tools here, with date, how the output was used, and what you personally changed or checked.
