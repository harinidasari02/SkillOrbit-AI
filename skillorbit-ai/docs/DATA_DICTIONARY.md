# Data dictionary

All IDs are immutable, zero-padded and identical across every file. Dates are ISO `YYYY-MM-DD`. All data is synthetic.
"Req" = required. Every `*_id` marked FK must exist in its parent table (checked by the validator).
**Proficiency scale:** 0 = never assessed (no row), 1 = awareness … 5 = expert. History is append-only.

## ID conventions
| Entity | Format | Example |
|---|---|---|
| Employee | `EMP-` + 6 digits | `EMP-000001` |
| Skill | `SKL-` + 6 digits | `SKL-000001` |
| Course | `CRS-` + 6 digits | `CRS-000001` |
| Enrollment | `ENR-<employee-id>-<course-id>` | `ENR-EMP-000001-CRS-000042` |
| Completion | `CMP-<year>-` + 6 digits | `CMP-2026-000001` |
| Budget | `BUD-<department>-<year>` | `BUD-ENG-2026` |
| Supporting | `ROL-`, `EVT-`, `ASM-`, `REC-`, `CASE-` + 6 digits | `ASM-000001` |

## Relationships and cardinality
| Relationship | Cardinality | Implemented by |
|---|---|---|
| Role → Employee | 1:N | `employees.role_id` |
| Department → Employee | 1:N | `employees.department` → `budgets.department` |
| Role ↔ Skill (target) | N:M, dated | `role_skill_targets` (effective_from / effective_to) |
| Employee ↔ Skill (proficiency) | N:M, append-only history | `employee_skill_assessments` |
| Course ↔ Skill | N:M | `course_skills` |
| Course ↔ Course (prerequisite) | N:M, acyclic | `course_prerequisites` |
| Employee ↔ Course | N:M, at most one enrollment per pair | `enrollments` (UNIQUE employee+course) |
| Enrollment → Event | 1:N (state history) | `enrollment_events` |
| Enrollment → Completion | 1:0..1 (a 2nd row exists only as a labelled `DUPLICATE_COMPLETION` fixture) | `completions.enrollment_id` |
| Completion → Reassessment | 1:0..N (optional; absence is meaningful) | `employee_skill_assessments.enrollment_id` |

Lifecycle of an enrollment: `ENROLLED → STARTED(IN_PROGRESS) → COMPLETED`, or `ENROLLED → CANCELLED`. Status always equals the latest event.

## roles (`ROL-`, PK role_id)
| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| role_id | string | Y | `ROL-\d{6}` | PK | |
| role_name | string | Y | text | | |
| department | string | Y | ENG, DATA, QA, OPS, BA, PMO | | must exist in `budgets` |

## skills (PK skill_id)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| skill_id | string | Y | `SKL-\d{6}` | PK | |
| skill_name | string | Y | "Category - Topic (Facet)" | Y | |
| category | string | Y | 10 categories | | |

## employees (PK employee_id)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| employee_id | string | Y | `EMP-\d{6}` | PK | |
| full_name | string | Y | synthetic names | | no real persons |
| email | string | Y | `employeeNNNNNN@example.com` | Y | reserved example domain |
| role_id | string | Y | FK roles | | |
| department | string | Y | department code | | equals the role's department |
| hire_date | date | Y | 2015-01-01 … 2025-09-30 | | before first assessment |
| status | string | Y | ACTIVE | | |

## role_skill_targets (PK role_id + skill_id + effective_from)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| role_id | string | Y | FK roles | | |
| skill_id | string | Y | FK skills | | |
| target_proficiency | int | Y | 1–5 | | |
| effective_from | date | Y | | | |
| effective_to | date | N | empty = open | | ≥ effective_from |

## courses (PK course_id)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| course_id | string | Y | `CRS-\d{6}` | PK | |
| course_name | string | Y | | | |
| cost | int | Y | currency units, 150–~9,150 | | > 0 |
| duration_hours | int | Y | 4–80 | | > 0; planned duration in days = hours |
| capacity | int | Y | seats, 8–40 | | > 0; concurrent non-cancelled enrollments ≤ capacity |
| level_taught | int | Y | 1–5 | | highest proficiency the course can bring a learner to |
| status | string | Y | ACTIVE, DISABLED | | |
| available_from / available_until | date | Y | | | until ≥ from; a course is available on day D iff ACTIVE and from ≤ D ≤ until |

## course_skills (PK course_id + skill_id) · course_prerequisites (PK course_id + prerequisite_course_id)
Both columns are FKs. Prerequisites always point to a lower-`level_taught` course teaching the same primary skill, so the graph is acyclic (also verified by topological sort); no self-reference.

## budgets (PK budget_id; UNIQUE department + fiscal_year)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| budget_id | string | Y | `BUD-<dept>-<year>` | PK | |
| department | string | Y | | Y/year | |
| fiscal_year | int | Y | 2026 | | |
| allocated_amount | long | Y | = 0.75 × headcount × per_employee_cap | | department spend ≤ allocated |
| per_employee_cap | long | Y | 6,000–14,000 | | employee spend ≤ cap |
`derived/budget_summary.csv` (committed = Σ cost of non-cancelled enrollments, remaining = allocated − committed) is **derived**, recomputed by the validator.

## enrollments (PK enrollment_id; UNIQUE employee_id + course_id)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| enrollment_id | string | Y | `ENR-<emp>-<course>` | PK | must equal the pattern |
| employee_id, course_id | string | Y | FKs | | |
| enrolled_date | date | Y | 2026-01-15 … as-of | | course available that day |
| planned_start_date | date | Y | enrolled + 7…14 days | | ≥ enrolled_date |
| planned_end_date | date | Y | start + duration − 1 | | ≥ start |
| status | string | Y | ENROLLED, IN_PROGRESS, COMPLETED, CANCELLED | | equals latest event |

## enrollment_events (PK event_id; append-only)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| event_id | string | Y | `EVT-\d{6}` | PK | |
| enrollment_id | string | Y | FK | | |
| event_type | string | Y | ENROLLED, STARTED, COMPLETED, CANCELLED | | legal transitions only |
| event_date | date | Y | ≤ as-of date | | |

## completions (PK completion_id)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| completion_id | string | Y | `CMP-2026-\d{6}` | PK | |
| enrollment_id | string | Y | FK | | enrollment must be COMPLETED |
| employee_id, course_id | string | Y | FKs | | agree with the enrollment |
| completion_date | date | Y | = planned_end_date, ≤ as-of | | |
| score | int | Y | 0–100 | | |
| passed | bool | Y | true iff score ≥ 60 | | |
A completion is **attendance/outcome of the course, not learning**. Learning is only recorded in a reassessment.

## employee_skill_assessments (PK assessment_id; append-only)

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| assessment_id | string | Y | `ASM-\d{6}` | PK | |
| employee_id, skill_id | string | Y | FKs | | |
| proficiency_level | int | Y | 0–5 | | ≤ max(previous, course level) for reassessments |
| previous_level | int | N | empty for INITIAL | | equals the level in history just before this row |
| assessed_date | date | Y | | | INITIAL after hire; REASSESSMENT 1–14 days after completion |
| source | string | Y | INITIAL, REASSESSMENT | | |
| enrollment_id | string | N | FK; required for REASSESSMENT | | tied to a *passed* completion |
Current proficiency = latest row per employee + skill.

## candidate_recommendations (PK recommendation_id) — input to the rule engine, no labels

| Field | Type | Req | Allowed / units | Unique | Rule |
|---|---|---|---|---|---|
| recommendation_id | string | Y | `REC-\d{6}` | PK | |
| employee_id, skill_id, course_id | string | Y | FKs | | |
| planning_date | date | Y | the dataset as-of date | | |

## Ground truth (separate folder `ground_truth/`, never joined into inputs)
* `edge_case_ground_truth.csv`: `case_id, issue_type, employee_id, course_id, related_course_id, expected_decision, evidence_note`
* `recommendation_decisions.csv`: `recommendation_id, issue_type (NONE for valid), expected_decision`
* `skill_gaps.csv`: `employee_id, skill_id, target_proficiency, current_proficiency, skill_gap` (recomputed by the validator)
* `../metrics.json`: evaluation metrics computed from source records.
