package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.Model.*;
import com.skillorbit.skillorbit_ai.synthetic.RecommendationRules.Decision;

import java.time.LocalDate;
import java.util.*;

/** Evaluation metrics computed from source records only (never generated independently). */
public final class Metrics {
    private Metrics() {}

    static double r4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    static double ratio(long a, long b) { return b == 0 ? 0.0 : r4((double) a / b); }

    public static Map<String, Object> compute(World w, List<String[]> gtRec, LocalDate T) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("as_of_date", T.toString());

        // ---- skill gaps and recommendation feasibility (every open gap x every course teaching that skill) ----
        long openGaps = 0, gapSum = 0, gapsWithFeasible = 0, pairs = 0;
        Set<String> empWithGap = new HashSet<>(), empWithFeasible = new HashSet<>();
        Map<String, Long> decisions = new TreeMap<>();
        for (Employee e : w.employees.values()) {
            for (Target t : w.targetByRole.getOrDefault(e.roleId(), Map.of()).values()) {
                int cur = w.currentLevel(e.id(), t.skillId(), T);
                if (cur >= t.target()) continue;
                openGaps++; gapSum += t.target() - cur; empWithGap.add(e.id());
                boolean feasible = false;
                for (String cid : w.coursesBySkill.getOrDefault(t.skillId(), List.of())) {
                    Decision d = RecommendationRules.evaluate(w, e.id(), t.skillId(), cid, T);
                    decisions.merge(d.name(), 1L, Long::sum); pairs++;
                    if (d == Decision.ACCEPT) feasible = true;
                }
                if (feasible) { gapsWithFeasible++; empWithFeasible.add(e.id()); }
            }
        }
        Map<String, Object> gaps = new LinkedHashMap<>();
        gaps.put("employees", w.employees.size());
        gaps.put("employees_with_open_gap", empWithGap.size());
        gaps.put("open_skill_gaps", openGaps);
        gaps.put("avg_gap_size_levels", ratio(gapSum, openGaps));
        gaps.put("gaps_with_at_least_one_feasible_course", gapsWithFeasible);
        gaps.put("gap_coverage_rate", ratio(gapsWithFeasible, openGaps));
        gaps.put("employees_with_feasible_recommendation_rate", ratio(empWithFeasible.size(), empWithGap.size()));
        m.put("gaps_and_coverage", gaps);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("evaluated_gap_course_pairs", pairs);
        rec.put("decision_counts", decisions);
        rec.put("feasible_recommendation_rate", ratio(decisions.getOrDefault("ACCEPT", 0L), pairs));
        m.put("recommendation_outcomes", rec);

        // ---- lifecycle and learning outcome (completion is not learning) ----
        Map<String, Long> status = new TreeMap<>();
        w.enrollments.values().forEach(e -> status.merge(e.status(), 1L, Long::sum));
        Set<String> firstSeen = new HashSet<>();
        long completions = 0, passed = 0, passedDue = 0, withGain = 0;
        Map<String, List<Assessment>> reByEnr = new HashMap<>();
        for (Assessment a : w.assessments) if ("REASSESSMENT".equals(a.source())) reByEnr.computeIfAbsent(a.enrollmentId(), k -> new ArrayList<>()).add(a);
        for (Completion c : w.completions) {
            if (!firstSeen.add(c.enrollmentId())) continue;   // count each enrollment's first completion only
            completions++;
            if (!c.passed()) continue;
            passed++;
            if (c.date().isAfter(T.minusDays(DataGenerator.REASSESS_WINDOW_DAYS))) continue;
            passedDue++;
            if (reByEnr.getOrDefault(c.enrollmentId(), List.of()).stream().anyMatch(a -> a.level() > a.prev())) withGain++;
        }
        long reRows = 0, gainSum = 0, reachedTarget = 0, targeted = 0;
        for (Assessment a : w.assessments) {
            if (!"REASSESSMENT".equals(a.source())) continue;
            reRows++; gainSum += a.level() - a.prev();
            Employee e = w.employees.get(a.empId());
            Target t = w.targetByRole.getOrDefault(e.roleId(), Map.of()).get(a.skillId());
            if (t != null && a.prev() < t.target()) { targeted++; if (a.level() >= t.target()) reachedTarget++; }
        }
        Map<String, Object> life = new LinkedHashMap<>();
        life.put("enrollment_status_counts", status);
        life.put("completion_pass_rate", ratio(passed, completions));
        life.put("passed_and_due_for_reassessment", passedDue);
        life.put("passed_completions_with_measured_gain", withGain);
        life.put("learning_gain_rate_given_pass", ratio(withGain, passedDue));
        life.put("avg_proficiency_gain_per_reassessment", ratio(gainSum, reRows));
        life.put("gap_closure_rate_on_targeted_reassessments", ratio(reachedTarget, targeted));
        m.put("lifecycle_and_learning", life);

        // ---- budget ----
        Map<String, Object> budget = new LinkedHashMap<>();
        long alloc = 0, spent = 0;
        for (Budget b : w.budgets.values()) {
            long c = w.committedByDept(b.dept());
            alloc += b.allocated(); spent += c;
            budget.put(b.id() + "_utilization", ratio(c, b.allocated()));
        }
        budget.put("overall_utilization", ratio(spent, alloc));
        budget.put("total_committed", spent);
        m.put("budget", budget);

        // ---- rule engine vs separate ground truth (edge cases and baseline) ----
        Map<String, String[]> byId = new HashMap<>();
        gtRec.forEach(r -> byId.put(r[0], r));
        Map<String, long[]> perIssue = new TreeMap<>();
        long match = 0;
        for (Candidate c : w.candidates) {
            String[] g = byId.get(c.id());
            Decision d = RecommendationRules.evaluate(w, c.empId(), c.skillId(), c.courseId(), c.planning());
            boolean ok = g != null && d.name().equals(g[2]);
            long[] x = perIssue.computeIfAbsent(g == null ? "UNLABELLED" : g[1], k -> new long[2]);
            x[0]++; if (ok) { x[1]++; match++; }
        }
        Map<String, Object> eval = new LinkedHashMap<>();
        eval.put("candidate_recommendations", w.candidates.size());
        eval.put("rule_engine_accuracy_vs_ground_truth", ratio(match, w.candidates.size()));
        perIssue.forEach((k, v) -> eval.put("detected_" + k, v[1] + "/" + v[0]));
        m.put("rule_engine_vs_ground_truth", eval);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static String toJson(Object o, int indent) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, indent);
        return sb.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o, int ind) {
        String pad = " ".repeat(ind + 2), end = " ".repeat(ind);
        if (o instanceof Map<?, ?> mm) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                sb.append(pad).append('"').append(e.getKey()).append("\": ");
                write(sb, e.getValue(), ind + 2);
                sb.append(++i < mm.size() ? ",\n" : "\n");
            }
            sb.append(end).append('}');
        } else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else sb.append('"').append(String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
}
