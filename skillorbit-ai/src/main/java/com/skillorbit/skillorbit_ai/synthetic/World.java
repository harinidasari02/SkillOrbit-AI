package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.Model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** In-memory dataset with the indexes the business rules need. Built by the generator or loaded from CSV. */
public final class World {
    public final Map<String, Role> roles = new LinkedHashMap<>();
    public final Map<String, Skill> skills = new LinkedHashMap<>();
    public final Map<String, Employee> employees = new LinkedHashMap<>();
    public final Map<String, Course> courses = new LinkedHashMap<>();
    public final Map<String, Budget> budgets = new LinkedHashMap<>();           // key = department
    public final List<Target> targets = new ArrayList<>();
    public final Map<String, Map<String, Target>> targetByRole = new LinkedHashMap<>();
    public final Map<String, List<String>> skillsByCourse = new LinkedHashMap<>();
    public final Map<String, List<String>> coursesBySkill = new LinkedHashMap<>();
    public final Map<String, List<String>> prereqs = new LinkedHashMap<>();
    public final Map<String, Enrollment> enrollments = new LinkedHashMap<>();
    public final List<Event> events = new ArrayList<>();
    public final List<Completion> completions = new ArrayList<>();
    public final List<Assessment> assessments = new ArrayList<>();
    public final List<Candidate> candidates = new ArrayList<>();

    private final Map<String, Map<String, List<Assessment>>> assessIdx = new HashMap<>();
    private final Map<String, Map<String, LocalDate>> passed = new HashMap<>();
    private final Map<String, Set<String>> enrolledCourses = new HashMap<>();
    private final Map<String, Long> committedEmp = new HashMap<>();
    private final Map<String, Long> committedDept = new HashMap<>();
    private final Map<String, List<LocalDate[]>> slots = new HashMap<>();

    public void addTarget(Target t) {
        targets.add(t);
        targetByRole.computeIfAbsent(t.roleId(), k -> new LinkedHashMap<>()).put(t.skillId(), t);
    }

    public void addCourseSkill(CourseSkill cs) {
        skillsByCourse.computeIfAbsent(cs.courseId(), k -> new ArrayList<>()).add(cs.skillId());
        coursesBySkill.computeIfAbsent(cs.skillId(), k -> new ArrayList<>()).add(cs.courseId());
    }

    public void addPrereq(Prereq p) { prereqs.computeIfAbsent(p.courseId(), k -> new ArrayList<>()).add(p.prereqId()); }

    public void addEnrollment(Enrollment e) {
        enrollments.put(e.id(), e);
        enrolledCourses.computeIfAbsent(e.empId(), k -> new HashSet<>()).add(e.courseId());
        if (!"CANCELLED".equals(e.status())) {
            Course c = courses.get(e.courseId());
            Employee emp = employees.get(e.empId());
            if (c != null) committedEmp.merge(e.empId(), (long) c.cost(), Long::sum);
            if (c != null && emp != null) committedDept.merge(emp.dept(), (long) c.cost(), Long::sum);
            if (e.start() != null && e.end() != null)
                slots.computeIfAbsent(e.courseId(), k -> new ArrayList<>()).add(new LocalDate[]{e.start(), e.end()});
        }
    }

    public void addCompletion(Completion c) {
        completions.add(c);
        if (c.passed()) passed.computeIfAbsent(c.empId(), k -> new HashMap<>()).putIfAbsent(c.courseId(), c.date());
    }

    public void addAssessment(Assessment a) {
        assessments.add(a);
        assessIdx.computeIfAbsent(a.empId(), k -> new HashMap<>()).computeIfAbsent(a.skillId(), k -> new ArrayList<>()).add(a);
    }

    /** Latest assessed level on or before {@code asOf}; 0 when never assessed (documented assumption). */
    public int currentLevel(String empId, String skillId, LocalDate asOf) {
        return levelBefore(empId, skillId, asOf, true, null);
    }

    /** Level using only assessments strictly before {@code date}, ignoring {@code self}. */
    public int levelStrictlyBefore(String empId, String skillId, LocalDate date, Assessment self) {
        return levelBefore(empId, skillId, date, false, self);
    }

    private int levelBefore(String empId, String skillId, LocalDate date, boolean inclusive, Assessment self) {
        Map<String, List<Assessment>> m = assessIdx.get(empId);
        if (m == null) return 0;
        List<Assessment> l = m.get(skillId);
        if (l == null) return 0;
        Assessment best = null;
        for (Assessment a : l) {
            if (a == self) continue;
            boolean ok = inclusive ? !a.date().isAfter(date) : a.date().isBefore(date);
            if (ok && (best == null || !a.date().isBefore(best.date()))) best = a;
        }
        return best == null ? 0 : best.level();
    }

    public LocalDate passedOn(String empId, String courseId) {
        Map<String, LocalDate> m = passed.get(empId);
        return m == null ? null : m.get(courseId);
    }

    public boolean hasEnrollment(String empId, String courseId) {
        Set<String> s = enrolledCourses.get(empId);
        return s != null && s.contains(courseId);
    }

    public long committedByEmployee(String empId) { return committedEmp.getOrDefault(empId, 0L); }
    public long committedByDept(String dept) { return committedDept.getOrDefault(dept, 0L); }

    /** min(employee cap - employee committed, department allocation - department committed). */
    public long remainingBudget(String empId) {
        Employee e = employees.get(empId);
        if (e == null) return 0;
        Budget b = budgets.get(e.dept());
        if (b == null) return 0;
        return Math.min(b.perEmployeeCap() - committedByEmployee(empId), b.allocated() - committedByDept(e.dept()));
    }

    public int overlapping(String courseId, LocalDate start, LocalDate end) {
        int n = 0;
        for (LocalDate[] s : slots.getOrDefault(courseId, List.of()))
            if (!s[0].isAfter(end) && !s[1].isBefore(start)) n++;
        return n;
    }

    public void copyMasterFrom(World g) {
        roles.putAll(g.roles); skills.putAll(g.skills); employees.putAll(g.employees);
        courses.putAll(g.courses); budgets.putAll(g.budgets);
        g.targets.forEach(this::addTarget);
        g.skillsByCourse.forEach((c, l) -> l.forEach(s -> addCourseSkill(new CourseSkill(c, s))));
        g.prereqs.forEach((c, l) -> l.forEach(p -> addPrereq(new Prereq(c, p))));
    }

    /** Loads a world from the {@code csv} directory. Raw rows are returned for key checks by the validator. */
    public static World load(Path csvDir, Map<String, List<String[]>> rawOut) throws IOException {
        World w = new World();
        Map<String, List<String[]>> raw = rawOut != null ? rawOut : new HashMap<>();
        for (String t : new String[]{"roles", "skills", "employees", "role_skill_targets", "courses", "course_skills",
                "course_prerequisites", "budgets", "enrollments", "enrollment_events", "completions",
                "employee_skill_assessments", "candidate_recommendations"}) {
            Path p = csvDir.resolve(t + ".csv");
            if (!Files.exists(p)) throw new IOException("Missing file: " + p);
            List<String[]> rows = Csv.read(p);
            raw.put(t, rows.subList(1, rows.size()));
        }
        raw.get("roles").forEach(r -> { Role x = Role.of(r); w.roles.put(x.id(), x); });
        raw.get("skills").forEach(r -> { Skill x = Skill.of(r); w.skills.put(x.id(), x); });
        raw.get("employees").forEach(r -> { Employee x = Employee.of(r); w.employees.put(x.id(), x); });
        raw.get("role_skill_targets").forEach(r -> w.addTarget(Target.of(r)));
        raw.get("courses").forEach(r -> { Course x = Course.of(r); w.courses.put(x.id(), x); });
        raw.get("course_skills").forEach(r -> w.addCourseSkill(CourseSkill.of(r)));
        raw.get("course_prerequisites").forEach(r -> w.addPrereq(Prereq.of(r)));
        raw.get("budgets").forEach(r -> { Budget x = Budget.of(r); w.budgets.put(x.dept(), x); });
        raw.get("enrollments").forEach(r -> w.addEnrollment(Enrollment.of(r)));
        raw.get("enrollment_events").forEach(r -> w.events.add(Event.of(r)));
        raw.get("completions").forEach(r -> w.addCompletion(Completion.of(r)));
        raw.get("employee_skill_assessments").forEach(r -> w.addAssessment(Assessment.of(r)));
        raw.get("candidate_recommendations").forEach(r -> w.candidates.add(Candidate.of(r)));
        return w;
    }
}
