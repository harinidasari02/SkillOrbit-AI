# Required edge cases: how each is built, labelled and verified

Baseline data is generated **valid first** (every enrollment is checked by the same rule engine the validator uses).
Edge cases are then injected as clearly labelled fixtures; labels live only in `ground_truth/`, never in input tables.
Counts below are per dataset: starter scale = 3 per type, full scale = 25 per type (`max(3, employees/200)`).

| issue_type | How the fixture is built | Expected decision | Where it lives |
|---|---|---|---|
| `PREREQUISITE_VIOLATION` | A candidate recommendation for a course whose prerequisite the employee has not passed. Chosen so that it is the **only** failing rule (it would be accepted if prerequisites were ignored). | `REJECT_PREREQUISITE`; `related_course_id` = the missing prerequisite | `candidate_recommendations` + `edge_case_ground_truth` |
| `BUDGET_EXCEEDED` | A candidate whose cost is greater than the employee's remaining budget, and otherwise eligible. | `REJECT_BUDGET` | same |
| `DUPLICATE_COMPLETION` | A second `completions` row and a second `COMPLETED` event for an already completed enrollment. The first completion is kept. | `REJECT_DUPLICATE_COMPLETION` | `completions`, `enrollment_events` + ground truth (names both completion ids) |
| `GAP_WITHOUT_REASSESSMENT` | A passed completion older than 14 days whose reassessment rows were withheld, for an employee whose latest enrollment it is, with the skill gap still open. | `KEEP_GAP_OPEN` (do not infer proficiency gain from attendance) | missing rows; evidence in ground truth |
| `COURSE_UNAVAILABLE` | A candidate for a DISABLED course or one whose window ends before the planning date, otherwise eligible. | `REJECT_UNAVAILABLE` | `candidate_recommendations` + ground truth |

## Evidence they are real and not just documented
* `DataValidator` re-reads the CSV files and, for every labelled case, re-checks that the anomaly is actually present
  (prerequisite really unmet, cost really above remaining budget, second completion really exists, no reassessment rows exist and the gap is still open, course really unavailable).
* The rule engine re-derives the expected decision for **every** candidate recommendation (valid and invalid) and must equal the label: see `rule_engine_vs_ground_truth` in `metrics.json`.
* The validator treats these fixtures as the *only* permitted exceptions: an unlabelled duplicate completion, missing reassessment or unmet prerequisite is reported as a failure.
* `SelfTest` deliberately corrupts a clean dataset (duplicate PK, orphan FK, unlabelled duplicate completion, tampered budget summary, leaked label column) and requires the validator to fail each time.

Full-scale evidence (5,000 employees): `docs/evidence/full/validation_report.txt` and `edge_case_ground_truth.csv`.
