# SkillOrbit AI

Java 17 / Spring Boot 3.5 application and deterministic synthetic-data generator that matches employee **skill gaps** to
**courses, prerequisites, capacity, availability and budgets**, tracks enrollment → completion → **reassessment**, and
labels five required edge cases in a separate ground-truth set. Ollama (local LLM) is optional and only *explains* a
decision; every decision is made by a deterministic rule engine.

## Quick start

Requirements: JDK 17+. Maven is only needed for the Spring app (`./mvnw` is included). No database, no Python.

```bash
# 1. dataset tools only (plain JDK; works without Maven/Spring)
sh scripts/synthetic.sh selftest                                   # rule unit tests + validator mutation tests
sh scripts/synthetic.sh generate --out synthetic-output/starter --starter
sh scripts/synthetic.sh generate --out synthetic-output/full       # 5,000 employees / 500 skills / 1,000 courses / 10,000 enrollments
sh scripts/synthetic.sh validate --out synthetic-output/starter
sh scripts/synthetic.sh repro --out synthetic-output/repro         # generate twice, compare SHA-256 of every file
#    Windows: scripts\synthetic.cmd <same arguments>

# 2. application (serves the UI at http://localhost:8080/ and the REST API)
./mvnw spring-boot:run            # uses the committed synthetic-output/starter dataset
# optional AI explanations: install Ollama, `ollama pull llama3.2:3b`, then run the app

# 3. tests
./mvnw test
```
Configuration (environment variables, no secrets): `OLLAMA_API_URL` (default `http://localhost:11434`),
`OLLAMA_MODEL` (`llama3.2:3b`), `DATASET_ROOT` (`synthetic-output`), `DATASET_SCALE` (`starter`).
Generator command, seed and scale are recorded in each dataset's `MANIFEST.txt`. Fixed seed **20261022**; the as-of/planning date is the
fixed constant **2026-10-28** (the wall clock is never used, so results never change by day).

## What is in the repository

| Path | Purpose |
|---|---|
| `src/main/java/.../synthetic/` | Plain-Java core: `DataGenerator`, `RecommendationRules` (single source of truth for decisions), `DataValidator`, `Metrics`, `Exporter` (CSV/JSON/SQL/manifest), `SyntheticCli`, `SelfTest` |
| `src/main/java/.../service`, `controller` | Spring layer: `DatasetService`, `RecommendationController`, `AdminController`, `OllamaService` |
| `src/main/resources/static/index.html` | Small UI: pick an employee, see gaps, feasible courses, rejection reasons, "Explain" (Ollama) |
| `synthetic-output/starter/` | Starter dataset (300 employees, 100 skills, 200 courses, 600 enrollments) |
| `synthetic-output/full/` | Generated full-scale CSV/JSON/SQL dataset (5,000 employees, 500 skills, 1,000 courses, 10,000 enrollments), manifest and validation report |
| `docs/evidence/full/` | Full-scale evidence: checksums, 48-check validation report, metrics, edge-case labels and reproducibility report |
| `docs/DATA_DICTIONARY.md`, `docs/EDGE_CASES.md`, `docs/AI_PROMPT_HISTORY.md` | Data dictionary, edge-case design, AI-use disclosure |

Dataset layout: `csv/` (13 input tables), `json/`, `sql/schema.sql` + `sql/seed_data.sql` (MySQL; own database `skillorbit_synthetic`),
`derived/budget_summary.csv`, `ground_truth/` (labels, **never** inside inputs), `metrics.json`, `MANIFEST.txt`, `validation_report.txt`.

## How the data is generated (and why)

1. **Master data first**: roles, skills (500 = 10 categories × 10 topics × 5 facets), employees, department budgets, role skill targets, courses (cost, duration, capacity, level taught, availability window, status), course↔skill, acyclic prerequisites (a lower-level course of the same primary skill), initial proficiency assessments.
2. **Valid baseline transactions**: each employee follows a timeline. For every enrollment the generator picks one of the employee's open skill gaps, then a course that the **same rule engine used by the app and validator** accepts at that date (available, not already enrolled, raises the level, prerequisites *passed* before, capacity free, budget remaining). ~15% are cancelled. Status (ENROLLED / IN_PROGRESS / COMPLETED) follows from dates relative to the as-of date. 12% of completions fail (score < 60).
3. **Learning is not attendance**: a *passed* completion produces reassessment rows 1–14 days later; gain is 1 level (2 for score ≥ 85), capped by the course level, and 10% show **no improvement**. Failed completions produce none. Nothing is overwritten: proficiency is an append-only history.
4. **Edge cases are injected after** the baseline as labelled fixtures (see `docs/EDGE_CASES.md`).
5. **Derived values** (budget committed/remaining, skill gaps, enrollment status, metrics) are computed from source records and re-checked by the validator.
6. **Reproducibility**: one `java.util.Random(seed)`, no wall-clock, deterministic iteration order, LF line endings.

## Rule engine (`RecommendationRules`)
Checks in this order: `REJECT_UNAVAILABLE` → `REJECT_ALREADY_ENROLLED` → `REJECT_NO_GAP_CLOSURE` → `REJECT_PREREQUISITE` → `REJECT_CAPACITY` → `REJECT_BUDGET` → `ACCEPT`.
Budget = min(employee cap − employee committed, department allocation − department committed).

## REST API (read-only except generation)
`GET /api/employees` · `GET /api/employees/{id}/skill-gaps` · `GET /api/employees/{id}/recommendations` ·
`GET /api/employees/{id}/skills/{skillId}/courses/{courseId}/decision` · `.../explanation` (one Ollama call, deterministic fallback) ·
`POST /api/admin/generate-dataset?scale=starter|full` · `GET /api/admin/validation` · `/validation/report` · `/metrics` · `/manifest` · `GET /api/health`.
`GET` never writes state, and Ollama is called only by `/explanation`, never in a loop.

## Validation and results
`DataValidator` reloads the exported CSVs (not generator memory) and runs **48 checks**: volume vs configuration, PK/natural-key uniqueness, ID formats,
FK integrity, enums/domains, date ordering, lifecycle transitions, completion consistency, business rules on *every* enrollment
(availability, prerequisites, gap closure, capacity sweep, employee/department budget), reassessment history, acyclic prerequisites,
budget and skill-gap arithmetic, ground-truth re-derivation, edge-case coverage, and absence of label leakage.
Results at full scale (`docs/evidence/full/`): **48 passed, 0 failed**; two runs with the same seed give **36 byte-identical files**.

| Metric (full scale) | Value |
|---|---|
| Open skill gaps / employees with ≥1 gap | 100,454 / 5,000 |
| Gaps with ≥1 feasible course | 60.4% |
| Employees with a feasible recommendation | 98.5% |
| Feasible share of evaluated gap×course pairs | 28.1% (rest: no gap closure 30.5%, prerequisite 18.8%, unavailable 11.9%, budget 8.2%, already enrolled 2.3%, capacity 0.2%) |
| Passed completions with measured proficiency gain | 89.8% (so completion ≠ learning) |
| Targeted reassessments that reach the role target | 32.1% |
| Rule engine vs separate ground truth | 1075 / 1075 |

## Assumptions, trade-offs and limitations (honest list)
* **Verification scope.** In my build environment Maven Central was unreachable, so `./mvnw test` and `spring-boot:run` were **not executed**. What *was* run: the whole project compiled with `javac --release 17` (Spring/JUnit replaced by minimal annotation stubs), the generator/validator/self-test for real, and all 9 JUnit test bodies through a stub runner (they pass). Spring wiring (component scan, property binding, JSON serialization of records) is therefore unverified until you run `./mvnw test` and start the app once.
* **No database.** The previous JPA/MySQL layer duplicated the data model and was the source of stale, inconsistent exports, so it was removed. A MySQL schema and seed are still exported (`sql/`) but the app does not use them; the SQL files were generated but not loaded into a live MySQL here.
* **Labels come from the documented rules**, and the validator re-checks them from raw CSV, but the validator and generator share `RecommendationRules`; a bug there would be consistent on both sides. The rule unit tests and the mutation tests mitigate this.
* A recommended course may raise a skill only part-way (gap closes over several courses); prerequisite chains are short (max depth 2); only 8 roles and synthetic names from small lists; distributions are reasoned assumptions, not empirical data.
* `0` proficiency means "never assessed" (no row). Capacity is checked on planned start/end dates. One `candidate_recommendation` per employee in the baseline (20% of employees).
* No external or personal data is used; emails use the reserved `example.com` domain. The full-scale output is included so reviewers can inspect the generated CSV/JSON/SQL files directly; regenerate it with the documented fixed-seed command when needed.
* Source-of-truth for AI use and prompts: `docs/AI_PROMPT_HISTORY.md` (must be completed by the project owner for tools I could not see).
