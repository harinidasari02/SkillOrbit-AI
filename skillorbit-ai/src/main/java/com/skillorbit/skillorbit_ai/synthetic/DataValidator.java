package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.Model.*;
import com.skillorbit.skillorbit_ai.synthetic.RecommendationRules.Decision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates an exported dataset by reloading the CSV files (not the generator's memory) and re-deriving
 * every rule independently. Labelled edge cases are the only permitted exceptions.
 */
public final class DataValidator {
    private DataValidator() {}

    static final class Acc {
        int n; final List<String> samples = new ArrayList<>();
        void add(String m) { n++; if (samples.size() < 5) samples.add(m); }
    }

    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public int passed, failed;
        void check(String name, Acc a) {
            if (a.n == 0) { passed++; lines.add("PASS  " + name); }
            else { failed++; lines.add("FAIL  " + name + " -> " + a.n + " violation(s), e.g. " + a.samples); }
        }
        void info(String s) { lines.add("INFO  " + s); }
        public boolean ok() { return failed == 0; }
    }

    static Acc acc() { return new Acc(); }

    static Acc dups(List<String[]> rows, int... cols) {
        Acc a = acc(); Set<String> seen = new HashSet<>();
        for (String[] r : rows) {
            StringBuilder k = new StringBuilder();
            for (int c : cols) k.append(r[c]).append('\u0001');
            if (!seen.add(k.toString())) a.add(k.toString().replace('\u0001', '|'));
        }
        return a;
    }

    static Acc format(List<String[]> rows, int col, String regex) {
        Acc a = acc(); Pattern p = Pattern.compile(regex);
        for (String[] r : rows) if (!p.matcher(r[col]).matches()) a.add(r[col]);
        return a;
    }

    static String note(String note, String key) {
        int i = note.indexOf(key);
        if (i < 0) return "";
        int s = i + key.length(), e = s;
        while (e < note.length() && note.charAt(e) != ' ' && note.charAt(e) != ';') e++;
        return note.substring(s, e);
    }

    /** Violation accumulator for the check currently being evaluated (validate() is synchronized). */
    private static Acc a;

    public static synchronized Report validate(Path out) throws IOException {
        Report rep = new Report();
        Map<String, List<String[]>> raw = new HashMap<>();
        final World w;
        try { w = World.load(out.resolve("csv"), raw); }
        catch (Exception ex) { rep.failed++; rep.lines.add("FAIL  load/parse CSV -> " + ex); write(out, rep); return rep; }

        Map<String, String> manifest = new HashMap<>();
        for (String line : Files.readAllLines(out.resolve("MANIFEST.txt"), StandardCharsets.UTF_8))
            for (String tok : line.split(" ")) { int i = tok.indexOf('='); if (i > 0) manifest.put(tok.substring(0, i), tok.substring(i + 1)); }
        LocalDate T = LocalDate.parse(manifest.get("as_of_date"));

        List<String[]> gtEdge = Csv.read(out.resolve("ground_truth/edge_case_ground_truth.csv"));
        List<String[]> gtRec = Csv.read(out.resolve("ground_truth/recommendation_decisions.csv"));
        gtEdge = gtEdge.subList(1, gtEdge.size()); gtRec = gtRec.subList(1, gtRec.size());

        // labelled exceptions
        Set<String> dupCompletionIds = new HashSet<>(), gapEnrollments = new HashSet<>();
        Map<String, Integer> caseCounts = new TreeMap<>();
        for (String[] r : gtEdge) {
            caseCounts.merge(r[1], 1, Integer::sum);
            if (r[1].equals("DUPLICATE_COMPLETION")) dupCompletionIds.add(note(r[6], "completion_id="));
            if (r[1].equals("GAP_WITHOUT_REASSESSMENT")) gapEnrollments.add("ENR-" + r[2] + "-" + r[3]);
        }

        // ---- volume vs configuration ----
        a = acc();
        expect(a, "employees", w.employees.size(), manifest.get("employees"));
        expect(a, "skills", w.skills.size(), manifest.get("skills"));
        expect(a, "courses", w.courses.size(), manifest.get("courses"));
        expect(a, "enrollments", w.enrollments.size(), manifest.get("enrollments"));
        rep.check("volume matches configured scale (employees/skills/courses/enrollments)", a);

        // ---- primary keys & natural keys ----
        rep.check("PK unique: roles", dups(raw.get("roles"), 0));
        rep.check("PK unique: skills", dups(raw.get("skills"), 0));
        rep.check("PK unique: employees", dups(raw.get("employees"), 0));
        rep.check("PK unique: role_skill_targets (role,skill,effective_from)", dups(raw.get("role_skill_targets"), 0, 1, 3));
        rep.check("PK unique: courses", dups(raw.get("courses"), 0));
        rep.check("PK unique: course_skills", dups(raw.get("course_skills"), 0, 1));
        rep.check("PK unique: course_prerequisites", dups(raw.get("course_prerequisites"), 0, 1));
        rep.check("PK unique: budgets", dups(raw.get("budgets"), 0));
        rep.check("PK unique: enrollments", dups(raw.get("enrollments"), 0));
        rep.check("PK unique: enrollment_events", dups(raw.get("enrollment_events"), 0));
        rep.check("PK unique: completions", dups(raw.get("completions"), 0));
        rep.check("PK unique: employee_skill_assessments", dups(raw.get("employee_skill_assessments"), 0));
        rep.check("PK unique: candidate_recommendations", dups(raw.get("candidate_recommendations"), 0));
        rep.check("natural key unique: one enrollment per employee+course", dups(raw.get("enrollments"), 1, 2));
        rep.check("natural key unique: employee email", dups(raw.get("employees"), 2));
        rep.check("natural key unique: one budget per department", dups(raw.get("budgets"), 1));

        // ---- ID conventions ----
        rep.check("ID format EMP-000001", format(raw.get("employees"), 0, "EMP-\\d{6}"));
        rep.check("ID format SKL-000001", format(raw.get("skills"), 0, "SKL-\\d{6}"));
        rep.check("ID format CRS-000001", format(raw.get("courses"), 0, "CRS-\\d{6}"));
        rep.check("ID format ENR-<employee-id>-<course-id>", format(raw.get("enrollments"), 0, "ENR-EMP-\\d{6}-CRS-\\d{6}"));
        rep.check("ID format CMP-2026-000001", format(raw.get("completions"), 0, "CMP-" + T.getYear() + "-\\d{6}"));
        rep.check("ID format BUD-<department>-2026", format(raw.get("budgets"), 0, "BUD-[A-Z]+-" + T.getYear()));
        a = acc();
        for (Enrollment e : w.enrollments.values()) if (!e.id().equals("ENR-" + e.empId() + "-" + e.courseId())) a.add(e.id());
        rep.check("enrollment id is exactly ENR-<employee_id>-<course_id>", a);
        a = acc();
        for (Budget b : w.budgets.values()) if (!b.id().equals("BUD-" + b.dept() + "-" + b.year())) a.add(b.id());
        rep.check("budget id matches department and fiscal year", a);

        // ---- foreign keys ----
        final int failedBeforeFk = rep.failed;
        a = acc();
        w.employees.values().forEach(e -> { if (!w.roles.containsKey(e.roleId())) a.add("employee " + e.id()); if (!w.budgets.containsKey(e.dept())) a.add("dept " + e.dept()); });
        w.targets.forEach(t -> { if (!w.roles.containsKey(t.roleId()) || !w.skills.containsKey(t.skillId())) a.add(t.roleId() + "/" + t.skillId()); });
        w.skillsByCourse.forEach((c, l) -> l.forEach(s -> { if (!w.courses.containsKey(c) || !w.skills.containsKey(s)) a.add(c + "/" + s); }));
        w.prereqs.forEach((c, l) -> l.forEach(p -> { if (!w.courses.containsKey(c) || !w.courses.containsKey(p)) a.add(c + "/" + p); }));
        rep.check("FK: employees/targets/course_skills/prerequisites reference existing parents", a);
        a = acc();
        w.enrollments.values().forEach(e -> { if (!w.employees.containsKey(e.empId()) || !w.courses.containsKey(e.courseId())) a.add(e.id()); });
        w.events.forEach(e -> { if (!w.enrollments.containsKey(e.enrollmentId())) a.add(e.id()); });
        w.completions.forEach(c -> {
            Enrollment e = w.enrollments.get(c.enrollmentId());
            if (e == null || !e.empId().equals(c.empId()) || !e.courseId().equals(c.courseId())) a.add(c.id());
        });
        rep.check("FK: enrollments/events/completions reference existing parents and agree on employee+course", a);
        a = acc();
        w.assessments.forEach(x -> {
            if (!w.employees.containsKey(x.empId()) || !w.skills.containsKey(x.skillId())
                || (x.enrollmentId() != null && !x.enrollmentId().isEmpty() && !w.enrollments.containsKey(x.enrollmentId()))) a.add(x.id());
        });
        w.candidates.forEach(c -> {
            if (!w.employees.containsKey(c.empId()) || !w.skills.containsKey(c.skillId()) || !w.courses.containsKey(c.courseId())) a.add(c.id());
        });
        rep.check("FK: assessments and candidate recommendations reference existing parents", a);
        if (rep.failed > failedBeforeFk) {
            rep.info("rule, lifecycle and ground-truth checks SKIPPED: they need valid joins (fix FK failures first)");
            write(out, rep);
            return rep;
        }

        // ---- enums / domains ----
        a = acc();
        Set<String> es = Set.of("ENROLLED", "IN_PROGRESS", "COMPLETED", "CANCELLED"), ev = Set.of("ENROLLED", "STARTED", "COMPLETED", "CANCELLED");
        w.enrollments.values().forEach(e -> { if (!es.contains(e.status())) a.add(e.id() + ":" + e.status()); });
        w.events.forEach(e -> { if (!ev.contains(e.type())) a.add(e.id()); });
        w.courses.values().forEach(c -> { if (!Set.of("ACTIVE", "DISABLED").contains(c.status())) a.add(c.id()); });
        w.employees.values().forEach(e -> { if (!"ACTIVE".equals(e.status())) a.add(e.id()); });
        w.assessments.forEach(x -> { if (!Set.of("INITIAL", "REASSESSMENT").contains(x.source())) a.add(x.id()); });
        rep.check("enums: statuses, event types, assessment sources", a);
        a = acc();
        w.targets.forEach(t -> { if (t.target() < 1 || t.target() > 5) a.add(t.roleId() + "/" + t.skillId()); });
        w.assessments.forEach(x -> { if (x.level() < 0 || x.level() > 5) a.add(x.id()); });
        w.courses.values().forEach(c -> { if (c.level() < 1 || c.level() > 5 || c.cost() <= 0 || c.hours() <= 0 || c.capacity() <= 0) a.add(c.id()); });
        w.completions.forEach(c -> { if (c.score() < 0 || c.score() > 100 || c.passed() != (c.score() >= DataGenerator.PASS_SCORE)) a.add(c.id()); });
        rep.check("domains: proficiency 0-5, targets 1-5, positive cost/hours/capacity, score 0-100, passed == score>=60", a);

        // ---- dates & lifecycle ----
        a = acc();
        w.courses.values().forEach(c -> { if (c.until().isBefore(c.from())) a.add(c.id()); });
        w.targets.forEach(t -> { if (t.effTo() != null && t.effTo().isBefore(t.effFrom())) a.add(t.roleId()); });
        w.enrollments.values().forEach(e -> {
            if (e.start().isBefore(e.enrolled()) || e.end().isBefore(e.start())) a.add(e.id());
            if (e.enrolled().isAfter(T)) a.add(e.id() + " enrolled in future");
        });
        w.events.forEach(e -> { if (e.date().isAfter(T)) a.add(e.id() + " future event"); });
        w.completions.forEach(c -> { if (c.date().isAfter(T)) a.add(c.id() + " future completion"); });
        w.assessments.forEach(x -> {
            if (x.date().isAfter(T)) a.add(x.id() + " future assessment");
            if ("INITIAL".equals(x.source()) && x.date().isBefore(w.employees.get(x.empId()).hireDate())) a.add(x.id() + " before hire");
        });
        rep.check("date ordering: hire < assessment, enrolled <= start <= end, nothing after as-of date " + T, a);

        Map<String, List<Event>> evByEnr = new HashMap<>();
        for (Event e : w.events) evByEnr.computeIfAbsent(e.enrollmentId(), k -> new ArrayList<>()).add(e);
        a = acc();
        for (Enrollment e : w.enrollments.values()) {
            List<Event> l = new ArrayList<>(evByEnr.getOrDefault(e.id(), List.of()));
            l.sort(Comparator.comparing(Event::date).thenComparingInt(Event::order));
            if (l.isEmpty() || !l.get(0).type().equals("ENROLLED")) { a.add(e.id() + " no ENROLLED first"); continue; }
            String derived = "ENROLLED";
            boolean dupOk = gapEnrollments.contains(e.id()) || dupCompletionIds.stream().anyMatch(id -> w.completions.stream().anyMatch(c -> c.id().equals(id) && c.enrollmentId().equals(e.id())));
            for (int i = 1; i < l.size(); i++) {
                String prev = l.get(i - 1).type(), cur = l.get(i).type();
                boolean legal = (prev.equals("ENROLLED") && (cur.equals("STARTED") || cur.equals("CANCELLED")))
                        || (prev.equals("STARTED") && cur.equals("COMPLETED")) || (prev.equals("COMPLETED") && cur.equals("COMPLETED") && dupOk);
                if (!legal) a.add(e.id() + " illegal transition " + prev + "->" + cur);
                derived = cur.equals("STARTED") ? "IN_PROGRESS" : cur;
            }
            if (!derived.equals(e.status())) a.add(e.id() + " status " + e.status() + " != event-derived " + derived);
        }
        rep.check("lifecycle: legal transitions only and enrollment status equals latest event", a);

        // ---- completions ----
        Map<String, List<Completion>> compByEnr = new HashMap<>();
        for (Completion c : w.completions) compByEnr.computeIfAbsent(c.enrollmentId(), k -> new ArrayList<>()).add(c);
        a = acc();
        for (Enrollment e : w.enrollments.values()) {
            List<Completion> cs = compByEnr.getOrDefault(e.id(), List.of());
            if (e.status().equals("COMPLETED") && cs.isEmpty()) a.add(e.id() + " COMPLETED without completion record");
            if (!e.status().equals("COMPLETED") && !cs.isEmpty()) a.add(e.id() + " has completion but status " + e.status());
            if (!cs.isEmpty() && !cs.get(0).date().equals(e.end())) a.add(e.id() + " completion date != planned end");
        }
        rep.check("completion <-> enrollment consistency (every COMPLETED has one, no other status has any)", a);
        a = acc();
        compByEnr.forEach((enr, cs) -> { for (int i = 1; i < cs.size(); i++) if (!dupCompletionIds.contains(cs.get(i).id())) a.add(enr + " duplicate " + cs.get(i).id()); });
        rep.check("no unlabelled duplicate completions (labelled DUPLICATE_COMPLETION rows excluded)", a);

        // ---- business rules on every enrollment ----
        a = acc();
        for (Enrollment e : w.enrollments.values()) {
            Course c = w.courses.get(e.courseId());
            if (!RecommendationRules.isAvailable(c, e.enrolled())) a.add(e.id() + " course unavailable on " + e.enrolled());
        }
        rep.check("rule: course ACTIVE and inside availability window on enrollment date", a);
        a = acc();
        for (Enrollment e : w.enrollments.values()) {
            String m = RecommendationRules.missingPrerequisite(w, e.empId(), e.courseId(), e.enrolled());
            if (m != null) a.add(e.id() + " missing prerequisite " + m);
        }
        rep.check("rule: every prerequisite passed before enrollment", a);
        a = acc();
        for (Enrollment e : w.enrollments.values()) {
            boolean closes = false;
            for (String s : w.skillsByCourse.getOrDefault(e.courseId(), List.of()))
                if (RecommendationRules.closesGap(w, e.empId(), s, w.courses.get(e.courseId()), e.enrolled())) closes = true;
            if (!closes) a.add(e.id());
        }
        rep.check("rule: every enrollment targets an open skill gap its course can raise", a);
        a = acc();
        Map<String, List<Enrollment>> byCourse = new HashMap<>();
        for (Enrollment e : w.enrollments.values()) if (!e.status().equals("CANCELLED")) byCourse.computeIfAbsent(e.courseId(), k -> new ArrayList<>()).add(e);
        byCourse.forEach((cid, l) -> {
            int cap = w.courses.get(cid).capacity();
            for (Enrollment x : l) {
                int n = 0;
                for (Enrollment y : l) if (!y.start().isAfter(x.start()) && !y.end().isBefore(x.start())) n++;
                if (n > cap) a.add(cid + " concurrent " + n + " > capacity " + cap);
            }
        });
        rep.check("rule: concurrent enrollments never exceed course capacity", a);
        a = acc();
        Map<String, Long> empSpend = new HashMap<>(), deptSpend = new HashMap<>();
        for (Enrollment e : w.enrollments.values()) if (!e.status().equals("CANCELLED")) {
            long cost = w.courses.get(e.courseId()).cost();
            empSpend.merge(e.empId(), cost, Long::sum);
            deptSpend.merge(w.employees.get(e.empId()).dept(), cost, Long::sum);
        }
        empSpend.forEach((emp, s) -> { if (s > w.budgets.get(w.employees.get(emp).dept()).perEmployeeCap()) a.add(emp + " spent " + s); });
        deptSpend.forEach((d, s) -> { if (s > w.budgets.get(d).allocated()) a.add(d + " spent " + s); });
        rep.check("rule: employee and department budgets never exceeded by enrollments", a);

        // ---- reassessment / learning outcome ----
        a = acc();
        Map<String, List<Assessment>> reByEnr = new HashMap<>();
        for (Assessment x : w.assessments) if ("REASSESSMENT".equals(x.source())) reByEnr.computeIfAbsent(x.enrollmentId(), k -> new ArrayList<>()).add(x);
        int passedDue = 0, withGain = 0;
        for (Enrollment e : w.enrollments.values()) {
            List<Completion> cs = compByEnr.getOrDefault(e.id(), List.of());
            if (cs.isEmpty()) continue;
            Completion c = cs.get(0);
            List<Assessment> re = reByEnr.getOrDefault(e.id(), List.of());
            boolean due = c.passed() && !c.date().isAfter(T.minusDays(DataGenerator.REASSESS_WINDOW_DAYS));
            if (!c.passed() && !re.isEmpty()) a.add(e.id() + " reassessed after a failed completion");
            if (due) {
                passedDue++;
                if (re.isEmpty() && !gapEnrollments.contains(e.id())) a.add(e.id() + " passed >14d ago, no reassessment, not labelled");
                if (re.stream().anyMatch(x -> x.level() > x.prev())) withGain++;
            }
        }
        rep.check("rule: passed completions older than 14 days have a reassessment (except labelled GAP_WITHOUT_REASSESSMENT)", a);
        a = acc();
        for (Assessment x : w.assessments) {
            if ("INITIAL".equals(x.source())) { if (x.prev() != null || (x.enrollmentId() != null && !x.enrollmentId().isEmpty())) a.add(x.id() + " bad INITIAL"); continue; }
            Enrollment e = w.enrollments.get(x.enrollmentId());
            Completion c = compByEnr.getOrDefault(x.enrollmentId(), List.of()).stream().findFirst().orElse(null);
            if (c == null || !c.passed() || !e.empId().equals(x.empId())) { a.add(x.id() + " not tied to a passed completion"); continue; }
            if (!x.date().isAfter(c.date()) || x.date().isAfter(c.date().plusDays(DataGenerator.REASSESS_WINDOW_DAYS))) a.add(x.id() + " outside reassessment window");
            int prior = w.levelStrictlyBefore(x.empId(), x.skillId(), x.date(), x);
            if (x.prev() == null || x.prev() != prior) a.add(x.id() + " previous_level " + x.prev() + " != history " + prior);
            if (x.level() < prior || x.level() > Math.max(prior, w.courses.get(e.courseId()).level())) a.add(x.id() + " level jump not explained by course level");
            if (!w.skillsByCourse.getOrDefault(e.courseId(), List.of()).contains(x.skillId())) a.add(x.id() + " skill not taught by course");
        }
        rep.check("history: reassessment rows append-only, previous_level matches history, gain bounded by course level", a);

        // ---- prerequisite graph ----
        a = acc();
        w.prereqs.forEach((c, l) -> { if (l.contains(c)) a.add("self " + c); });
        Map<String, Integer> indeg = new HashMap<>();
        w.courses.keySet().forEach(c -> indeg.put(c, 0));
        w.prereqs.forEach((c, l) -> indeg.merge(c, l.size(), Integer::sum));
        Deque<String> q = new ArrayDeque<>();
        indeg.forEach((c, d) -> { if (d == 0) q.add(c); });
        Map<String, List<String>> dependents = new HashMap<>();
        w.prereqs.forEach((c, l) -> l.forEach(p -> dependents.computeIfAbsent(p, k -> new ArrayList<>()).add(c)));
        int seen = 0;
        while (!q.isEmpty()) { String c = q.poll(); seen++; for (String d : dependents.getOrDefault(c, List.of())) if (indeg.merge(d, -1, Integer::sum) == 0) q.add(d); }
        if (seen != w.courses.size()) a.add("cycle detected among prerequisites");
        rep.check("prerequisite graph is acyclic with no self-reference", a);
        int withPre = w.prereqs.size();
        int maxChain = 0;
        Map<String, Integer> depth = new HashMap<>();
        for (String c : w.courses.keySet()) maxChain = Math.max(maxChain, chain(w, c, depth));
        rep.info("courses with prerequisites: " + withPre + " of " + w.courses.size() + "; longest prerequisite chain: " + maxChain);

        // ---- derived values reconcile ----
        a = acc();
        List<String[]> bs = Csv.read(out.resolve("derived/budget_summary.csv"));
        for (String[] r : bs.subList(1, bs.size())) {
            Budget b = w.budgets.get(r[1]);
            long committed = deptSpend.getOrDefault(r[1], 0L);
            if (b == null || Long.parseLong(r[2]) != b.allocated() || Long.parseLong(r[3]) != committed || Long.parseLong(r[4]) != b.allocated() - committed) a.add(r[0]);
        }
        if (bs.size() - 1 != w.budgets.size()) a.add("budget_summary row count");
        rep.check("arithmetic: budget_summary committed/remaining recomputed from enrollments and courses", a);
        a = acc();
        List<String[]> gaps = Csv.read(out.resolve("ground_truth/skill_gaps.csv"));
        for (String[] r : gaps.subList(1, gaps.size())) {
            Employee e = w.employees.get(r[0]);
            int cur = w.currentLevel(r[0], r[1], T);
            Target t = e == null ? null : w.targetByRole.getOrDefault(e.roleId(), Map.of()).get(r[1]);
            if (t == null || Integer.parseInt(r[2]) != t.target() || Integer.parseInt(r[3]) != cur || Integer.parseInt(r[4]) != Math.max(0, t.target() - cur)) a.add(r[0] + "/" + r[1]);
        }
        rep.check("arithmetic: skill_gaps = max(0, target - latest assessed level) for every role target", a);

        // ---- ground truth, edge cases, leakage ----
        a = acc();
        Set<String> recIds = new HashSet<>();
        w.candidates.forEach(c -> recIds.add(c.id()));
        if (gtRec.size() != w.candidates.size()) a.add("decision rows " + gtRec.size() + " != candidates " + w.candidates.size());
        Map<String, String[]> gtRecById = new HashMap<>();
        gtRec.forEach(r -> gtRecById.put(r[0], r));
        for (Candidate c : w.candidates) {
            String[] g = gtRecById.get(c.id());
            if (g == null) { a.add(c.id() + " has no ground-truth decision"); continue; }
            Decision d = RecommendationRules.evaluate(w, c.empId(), c.skillId(), c.courseId(), c.planning());
            if (!d.name().equals(g[2])) a.add(c.id() + " engine=" + d + " label=" + g[2]);
        }
        rep.check("ground truth: rule engine re-derives the expected decision for every candidate recommendation", a);
        a = acc();
        for (String[] r : gtEdge) {
            if (!w.employees.containsKey(r[2]) || !w.courses.containsKey(r[3])) a.add(r[0] + " bad FK");
            switch (r[1]) {
                case "PREREQUISITE_VIOLATION" -> {
                    if (!w.prereqs.getOrDefault(r[3], List.of()).contains(r[4]) || (w.passedOn(r[2], r[4]) != null && !w.passedOn(r[2], r[4]).isAfter(T))) a.add(r[0] + " prerequisite actually met");
                }
                case "BUDGET_EXCEEDED" -> { if (w.courses.get(r[3]).cost() <= w.remainingBudget(r[2])) a.add(r[0] + " within budget"); }
                case "COURSE_UNAVAILABLE" -> { if (RecommendationRules.isAvailable(w.courses.get(r[3]), T)) a.add(r[0] + " is available"); }
                case "DUPLICATE_COMPLETION" -> {
                    String id = note(r[6], "completion_id=");
                    Completion c = w.completions.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
                    if (c == null || compByEnr.get(c.enrollmentId()).size() < 2 || compByEnr.get(c.enrollmentId()).get(0).id().equals(id)) a.add(r[0] + " not a second completion");
                }
                case "GAP_WITHOUT_REASSESSMENT" -> {
                    String enr = "ENR-" + r[2] + "-" + r[3];
                    boolean open = false;
                    Employee e = w.employees.get(r[2]);
                    for (String s : w.skillsByCourse.getOrDefault(r[3], List.of())) {
                        Target t = w.targetByRole.get(e.roleId()).get(s);
                        if (t != null && w.currentLevel(r[2], s, T) < t.target()) open = true;
                    }
                    if (!compByEnr.containsKey(enr) || !reByEnr.getOrDefault(enr, List.of()).isEmpty() || !open) a.add(r[0] + " reassessment exists or gap closed");
                }
                default -> a.add(r[0] + " unknown issue_type " + r[1]);
            }
        }
        rep.check("ground truth: every labelled edge case really exhibits its anomaly", a);
        a = acc();
        for (String t : new String[]{"PREREQUISITE_VIOLATION", "BUDGET_EXCEEDED", "DUPLICATE_COMPLETION", "GAP_WITHOUT_REASSESSMENT", "COURSE_UNAVAILABLE"})
            if (caseCounts.getOrDefault(t, 0) == 0) a.add("no fixture for " + t);
        rep.check("coverage: all 5 required edge cases have at least one labelled fixture", a);
        rep.info("edge-case fixture counts: " + caseCounts);
        a = acc();
        for (Map.Entry<String, List<String[]>> en : raw.entrySet()) { /* headers are checked below from files */ }
        for (String t : raw.keySet()) {
            String header = Files.readAllLines(out.resolve("csv").resolve(t + ".csv"), StandardCharsets.UTF_8).get(0).toLowerCase();
            for (String bad : new String[]{"issue_type", "ground_truth", "expected_decision", "evidence_note"})
                if (header.contains(bad)) a.add(t + " leaks " + bad);
        }
        rep.check("separation: no ground-truth columns inside input tables", a);

        // ---- distribution summary (informational, evidence for realism review) ----
        Map<String, Integer> st = new TreeMap<>();
        w.enrollments.values().forEach(e -> st.merge(e.status(), 1, Integer::sum));
        long passedC = w.completions.stream().filter(Completion::passed).count();
        rep.info("enrollment status mix: " + st);
        rep.info("completions: " + w.completions.size() + ", passed " + passedC + "; passed-and-due with measured proficiency gain: " + withGain + " of " + passedDue + " (completion != learning outcome)");
        rep.info("course cost range " + w.courses.values().stream().mapToInt(Course::cost).min().orElse(0) + ".." + w.courses.values().stream().mapToInt(Course::cost).max().orElse(0)
                + "; unavailable at " + T + ": " + w.courses.values().stream().filter(c -> !RecommendationRules.isAvailable(c, T)).count() + " of " + w.courses.size());
        write(out, rep);
        return rep;
    }

    static int chain(World w, String c, Map<String, Integer> memo) {
        Integer m = memo.get(c);
        if (m != null) return m;
        memo.put(c, 0);
        int best = 0;
        for (String p : w.prereqs.getOrDefault(c, List.of())) best = Math.max(best, 1 + chain(w, p, memo));
        memo.put(c, best);
        return best;
    }

    static void expect(Acc a, String name, int actual, String expected) {
        if (expected == null || Integer.parseInt(expected) != actual) a.add(name + " actual=" + actual + " expected=" + expected);
    }

    static void write(Path out, Report r) throws IOException {
        StringBuilder sb = new StringBuilder("SkillOrbit validation report\n============================\n");
        r.lines.forEach(l -> sb.append(l).append('\n'));
        sb.append("\nSUMMARY: ").append(r.passed).append(" passed, ").append(r.failed).append(" failed\n");
        Files.writeString(out.resolve("validation_report.txt"), sb.toString(), StandardCharsets.UTF_8);
    }
}
