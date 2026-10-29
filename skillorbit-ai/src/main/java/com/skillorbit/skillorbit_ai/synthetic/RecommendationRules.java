package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.Model.*;

import java.time.LocalDate;

/**
 * Single source of truth for recommendation eligibility. Deterministic business rules only;
 * an LLM (Ollama) may explain a decision but never makes or overrides one.
 * Check order: availability, duplicate enrollment, gap closure, prerequisites, capacity, budget.
 */
public final class RecommendationRules {
    private RecommendationRules() {}

    public enum Decision {
        ACCEPT, REJECT_UNAVAILABLE, REJECT_ALREADY_ENROLLED, REJECT_NO_GAP_CLOSURE,
        REJECT_PREREQUISITE, REJECT_CAPACITY, REJECT_BUDGET
    }

    public static final int START_LAG_DAYS = 7;

    public static boolean isAvailable(Course c, LocalDate date) {
        return "ACTIVE".equals(c.status()) && !date.isBefore(c.from()) && !date.isAfter(c.until());
    }

    public static int durationDays(Course c) { return Math.max(1, c.hours()); }

    /** First prerequisite course the employee has not passed on or before {@code date}; null if all are met. */
    public static String missingPrerequisite(World w, String empId, String courseId, LocalDate date) {
        for (String p : w.prereqs.getOrDefault(courseId, java.util.List.of())) {
            LocalDate done = w.passedOn(empId, p);
            if (done == null || done.isAfter(date)) return p;
        }
        return null;
    }

    /** True when the course teaches the skill, the role targets it, and the course level exceeds current proficiency. */
    public static boolean closesGap(World w, String empId, String skillId, Course c, LocalDate date) {
        Employee e = w.employees.get(empId);
        if (e == null || !w.skillsByCourse.getOrDefault(c.id(), java.util.List.of()).contains(skillId)) return false;
        Target t = w.targetByRole.getOrDefault(e.roleId(), java.util.Map.of()).get(skillId);
        if (t == null || t.effFrom().isAfter(date)) return false;
        int cur = w.currentLevel(empId, skillId, date);
        return cur < t.target() && c.level() > cur;
    }

    public static Decision evaluate(World w, String empId, String skillId, String courseId, LocalDate date, Decision skip) {
        Course c = w.courses.get(courseId);
        if (c == null) return Decision.REJECT_UNAVAILABLE;
        LocalDate start = date.plusDays(START_LAG_DAYS);
        return evaluate(w, empId, skillId, courseId, date, start, start.plusDays(durationDays(c) - 1), skip);
    }

    public static Decision evaluate(World w, String empId, String skillId, String courseId, LocalDate date,
                                    LocalDate start, LocalDate end, Decision skip) {
        Course c = w.courses.get(courseId);
        if (c == null) return Decision.REJECT_UNAVAILABLE;
        if (skip != Decision.REJECT_UNAVAILABLE && !isAvailable(c, date)) return Decision.REJECT_UNAVAILABLE;
        if (w.hasEnrollment(empId, courseId)) return Decision.REJECT_ALREADY_ENROLLED;
        if (!closesGap(w, empId, skillId, c, date)) return Decision.REJECT_NO_GAP_CLOSURE;
        if (skip != Decision.REJECT_PREREQUISITE && missingPrerequisite(w, empId, courseId, date) != null)
            return Decision.REJECT_PREREQUISITE;
        if (w.overlapping(courseId, start, end) >= c.capacity()) return Decision.REJECT_CAPACITY;
        if (skip != Decision.REJECT_BUDGET && c.cost() > w.remainingBudget(empId)) return Decision.REJECT_BUDGET;
        return Decision.ACCEPT;
    }

    public static Decision evaluate(World w, String empId, String skillId, String courseId, LocalDate date) {
        return evaluate(w, empId, skillId, courseId, date, null);
    }
}
