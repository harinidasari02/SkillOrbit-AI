package com.skillorbit.skillorbit_ai.synthetic;

import java.time.LocalDate;

/** Immutable records for every entity. Each record knows its CSV header and row form. */
public final class Model {
    private Model() {}

    static LocalDate d(String s) { return (s == null || s.isBlank()) ? null : LocalDate.parse(s.trim()); }
    static String s(Object o) { return o == null ? "" : o.toString(); }
    static Integer i(String s) { return (s == null || s.isBlank()) ? null : Integer.valueOf(s.trim()); }

    public record Role(String id, String name, String dept) {
        public static final String[] H = {"role_id", "role_name", "department"};
        String[] row() { return new String[]{id, name, dept}; }
        static Role of(String[] r) { return new Role(r[0], r[1], r[2]); }
    }

    public record Skill(String id, String name, String category) {
        public static final String[] H = {"skill_id", "skill_name", "category"};
        String[] row() { return new String[]{id, name, category}; }
        static Skill of(String[] r) { return new Skill(r[0], r[1], r[2]); }
    }

    public record Employee(String id, String name, String email, String roleId, String dept,
                           LocalDate hireDate, String status) {
        public static final String[] H = {"employee_id", "full_name", "email", "role_id", "department", "hire_date", "status"};
        String[] row() { return new String[]{id, name, email, roleId, dept, s(hireDate), status}; }
        static Employee of(String[] r) { return new Employee(r[0], r[1], r[2], r[3], r[4], d(r[5]), r[6]); }
    }

    public record Target(String roleId, String skillId, int target, LocalDate effFrom, LocalDate effTo) {
        public static final String[] H = {"role_id", "skill_id", "target_proficiency", "effective_from", "effective_to"};
        String[] row() { return new String[]{roleId, skillId, s(target), s(effFrom), s(effTo)}; }
        static Target of(String[] r) { return new Target(r[0], r[1], Integer.parseInt(r[2]), d(r[3]), d(r[4])); }
    }

    public record Course(String id, String name, int cost, int hours, int capacity, int level,
                         String status, LocalDate from, LocalDate until) {
        public static final String[] H = {"course_id", "course_name", "cost", "duration_hours", "capacity",
                "level_taught", "status", "available_from", "available_until"};
        String[] row() { return new String[]{id, name, s(cost), s(hours), s(capacity), s(level), status, s(from), s(until)}; }
        static Course of(String[] r) {
            return new Course(r[0], r[1], Integer.parseInt(r[2]), Integer.parseInt(r[3]), Integer.parseInt(r[4]),
                    Integer.parseInt(r[5]), r[6], d(r[7]), d(r[8]));
        }
    }

    public record CourseSkill(String courseId, String skillId) {
        public static final String[] H = {"course_id", "skill_id"};
        String[] row() { return new String[]{courseId, skillId}; }
        static CourseSkill of(String[] r) { return new CourseSkill(r[0], r[1]); }
    }

    public record Prereq(String courseId, String prereqId) {
        public static final String[] H = {"course_id", "prerequisite_course_id"};
        String[] row() { return new String[]{courseId, prereqId}; }
        static Prereq of(String[] r) { return new Prereq(r[0], r[1]); }
    }

    public record Budget(String id, String dept, int year, long allocated, long perEmployeeCap) {
        public static final String[] H = {"budget_id", "department", "fiscal_year", "allocated_amount", "per_employee_cap"};
        String[] row() { return new String[]{id, dept, s(year), s(allocated), s(perEmployeeCap)}; }
        static Budget of(String[] r) { return new Budget(r[0], r[1], Integer.parseInt(r[2]), Long.parseLong(r[3]), Long.parseLong(r[4])); }
    }

    public record Enrollment(String id, String empId, String courseId, LocalDate enrolled, LocalDate start,
                             LocalDate end, String status) {
        public static final String[] H = {"enrollment_id", "employee_id", "course_id", "enrolled_date",
                "planned_start_date", "planned_end_date", "status"};
        String[] row() { return new String[]{id, empId, courseId, s(enrolled), s(start), s(end), status}; }
        static Enrollment of(String[] r) { return new Enrollment(r[0], r[1], r[2], d(r[3]), d(r[4]), d(r[5]), r[6]); }
    }

    public record Event(String id, String enrollmentId, String type, LocalDate date) {
        public static final String[] H = {"event_id", "enrollment_id", "event_type", "event_date"};
        String[] row() { return new String[]{id, enrollmentId, type, s(date)}; }
        static Event of(String[] r) { return new Event(r[0], r[1], r[2], d(r[3])); }
        int order() { return switch (type) { case "ENROLLED" -> 0; case "STARTED" -> 1; case "COMPLETED" -> 2; default -> 3; }; }
    }

    public record Completion(String id, String enrollmentId, String empId, String courseId, LocalDate date,
                             int score, boolean passed) {
        public static final String[] H = {"completion_id", "enrollment_id", "employee_id", "course_id",
                "completion_date", "score", "passed"};
        String[] row() { return new String[]{id, enrollmentId, empId, courseId, s(date), s(score), s(passed)}; }
        static Completion of(String[] r) {
            return new Completion(r[0], r[1], r[2], r[3], d(r[4]), Integer.parseInt(r[5]), Boolean.parseBoolean(r[6]));
        }
    }

    /** Append-only proficiency history. Current proficiency = latest row per employee+skill. */
    public record Assessment(String id, String empId, String skillId, int level, Integer prev, LocalDate date,
                             String source, String enrollmentId) {
        public static final String[] H = {"assessment_id", "employee_id", "skill_id", "proficiency_level",
                "previous_level", "assessed_date", "source", "enrollment_id"};
        String[] row() { return new String[]{id, empId, skillId, s(level), s(prev), s(date), source, enrollmentId == null ? "" : enrollmentId}; }
        static Assessment of(String[] r) {
            return new Assessment(r[0], r[1], r[2], Integer.parseInt(r[3]), i(r[4]), d(r[5]), r[6], r.length > 7 ? r[7] : "");
        }
        Assessment withId(String newId) { return new Assessment(newId, empId, skillId, level, prev, date, source, enrollmentId); }
    }

    public record Candidate(String id, String empId, String skillId, String courseId, LocalDate planning) {
        public static final String[] H = {"recommendation_id", "employee_id", "skill_id", "course_id", "planning_date"};
        String[] row() { return new String[]{id, empId, skillId, courseId, s(planning)}; }
        static Candidate of(String[] r) { return new Candidate(r[0], r[1], r[2], r[3], d(r[4])); }
    }
}
