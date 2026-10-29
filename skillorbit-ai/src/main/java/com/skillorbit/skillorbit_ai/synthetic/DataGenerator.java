package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.Model.*;
import com.skillorbit.skillorbit_ai.synthetic.RecommendationRules.Decision;

import java.time.LocalDate;
import java.util.*;

/**
 * Deterministic synthetic data generator. One seeded {@link Random}, fixed as-of date (never the wall clock).
 * Order: master data -> valid baseline transactions (every enrollment is checked against the same rules the
 * validator uses) -> labelled edge cases -> sequential IDs -> ground truth.
 */
public final class DataGenerator {
    private DataGenerator() {}

    public static final String VERSION = "2.0";
    public static final int PASS_SCORE = 60;
    public static final int REASSESS_WINDOW_DAYS = 14;

    public record Config(long seed, int employees, int skills, int courses, int enrollments, LocalDate asOf) {
        public static Config full() { return new Config(20261022L, 5000, 500, 1000, 10000, LocalDate.of(2026, 10, 28)); }
        public static Config starter() { return new Config(20261022L, 300, 100, 200, 600, LocalDate.of(2026, 10, 28)); }
    }

    public static final class Generated {
        public World world;
        public Config cfg;
        public List<String[]> gtEdge = new ArrayList<>();
        public List<String[]> gtRecommendations = new ArrayList<>();
        public List<String[]> gtGaps = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }

    static final String[] CATS = {"Java", "Python", "Data", "Testing", "DevOps", "Cloud", "Database", "Web", "Security", "Delivery"};
    static final String[][] TOPICS = {
        {"Core Language", "Collections", "Streams", "Concurrency", "JVM Tuning", "Generics", "Exceptions", "I/O", "Spring Boot", "JPA"},
        {"Core Language", "Packaging", "Async", "Pandas", "NumPy", "Flask", "Django", "Typing", "Scripting", "Testing Tools"},
        {"SQL Analytics", "Visualization", "Statistics", "ETL", "Data Modeling", "Dashboards", "Forecasting", "Data Quality", "Warehousing", "Reporting"},
        {"Unit Testing", "Integration Testing", "Test Automation", "Selenium", "API Testing", "Load Testing", "Test Design", "Mocking", "Exploratory Testing", "BDD"},
        {"CI/CD", "Docker", "Kubernetes", "Terraform", "Monitoring", "Logging", "Linux Admin", "Git Workflows", "Ansible", "Release Engineering"},
        {"AWS Basics", "Azure Basics", "Serverless", "Cloud Networking", "Cloud Storage", "IAM", "Cost Management", "Cloud Migration", "Observability", "Containers on Cloud"},
        {"Relational Design", "Query Tuning", "Indexing", "Transactions", "NoSQL", "Replication", "Backup", "Stored Procedures", "Migrations", "Caching"},
        {"HTML/CSS", "JavaScript", "React", "Accessibility", "REST Design", "Web Performance", "State Management", "Browser Tooling", "UX Basics", "TypeScript"},
        {"Secure Coding", "AuthN/AuthZ", "Threat Modeling", "Cryptography", "Vulnerability Mgmt", "Secrets Mgmt", "Network Security", "Compliance", "Incident Response", "Pen Testing Basics"},
        {"Agile", "Scrum", "Estimation", "Requirements", "Stakeholder Mgmt", "Risk Mgmt", "Roadmapping", "Retrospectives", "Business Analysis", "Process Modeling"}};
    static final String[] FACETS = {"Basics", "Design", "Testing", "Performance", "Operations"};
    static final String[] LEVELS = {"", "Intro", "Foundation", "Intermediate", "Advanced", "Expert"};
    static final String[] FIRST = {"Aarav", "Meera", "Arjun", "Priya", "Rohan", "Ananya", "Vikram", "Sneha", "Kiran", "Divya",
        "Rahul", "Lakshmi", "Suresh", "Anjali", "Karthik", "Pooja", "Manoj", "Neha", "Sanjay", "Isha"};
    static final String[] LAST = {"Reddy", "Naidu", "Sharma", "Gupta", "Rao", "Patel", "Iyer", "Nair", "Singh", "Kumar",
        "Varma", "Das", "Mehta", "Joshi", "Menon", "Pillai", "Verma", "Bose", "Shetty", "Kulkarni"};
    static final String[][] ROLES = {
        {"Java Developer", "ENG"}, {"Python Developer", "ENG"}, {"Data Analyst", "DATA"}, {"Software Tester", "QA"},
        {"DevOps Engineer", "OPS"}, {"Business Analyst", "BA"}, {"Project Manager", "PMO"}, {"UI Developer", "ENG"}};
    static final int[][] ROLE_CATS = {{0, 6}, {1, 2}, {2, 6}, {3, 1}, {4, 5}, {9, 2}, {9, 8}, {7, 3}};

    static String id(String prefix, int n) { return String.format("%s-%06d", prefix, n); }

    static int pick(Random r, int... weights) {
        int total = 0;
        for (int w : weights) total += w;
        int x = r.nextInt(total);
        for (int i = 0; i < weights.length; i++) { x -= weights[i]; if (x < 0) return i; }
        return weights.length - 1;
    }

    public static Generated generate(Config cfg) {
        Random rng = new Random(cfg.seed());
        LocalDate T = cfg.asOf();
        World g = new World();
        Generated out = new Generated();
        out.cfg = cfg;

        // ---------- 1. master data ----------
        for (int i = 0; i < ROLES.length; i++) g.roles.put(id("ROL", i + 1), new Role(id("ROL", i + 1), ROLES[i][0], ROLES[i][1]));
        for (int i = 0; i < cfg.skills(); i++) {
            int cat = i % 10, topic = (i / 10) % 10, facet = (i / 100) % 5;
            String sid = id("SKL", i + 1);
            g.skills.put(sid, new Skill(sid, CATS[cat] + " - " + TOPICS[cat][topic] + " (" + FACETS[facet] + ")", CATS[cat]));
        }
        List<String> skillIds = new ArrayList<>(g.skills.keySet());
        List<Role> roleList = new ArrayList<>(g.roles.values());

        Map<String, Integer> deptHeadcount = new LinkedHashMap<>();
        for (int i = 0; i < cfg.employees(); i++) {
            Role r = roleList.get(rng.nextInt(roleList.size()));
            String eid = id("EMP", i + 1);
            LocalDate hire = LocalDate.of(2015, 1, 1).plusDays(rng.nextInt(3925));
            String name = FIRST[rng.nextInt(FIRST.length)] + " " + LAST[rng.nextInt(LAST.length)];
            g.employees.put(eid, new Employee(eid, name, "employee" + String.format("%06d", i + 1) + "@example.com",
                    r.id(), r.dept(), hire, "ACTIVE"));
            deptHeadcount.merge(r.dept(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : deptHeadcount.entrySet()) {
            long cap = 6000 + 500L * rng.nextInt(17);
            g.budgets.put(e.getKey(), new Budget("BUD-" + e.getKey() + "-" + T.getYear(), e.getKey(), T.getYear(),
                    Math.round(e.getValue() * cap * 0.75), cap));
        }

        int tpr = Math.min(30, Math.max(5, cfg.skills() / 4));
        for (int ri = 0; ri < roleList.size(); ri++) {
            Set<Integer> chosen = new LinkedHashSet<>();
            int perCat = Math.max(1, cfg.skills() / 10);
            while (chosen.size() < tpr) {
                int idx = rng.nextInt(100) < 65
                        ? ROLE_CATS[ri][rng.nextInt(2)] + 10 * rng.nextInt(perCat)
                        : rng.nextInt(cfg.skills());
                if (idx < cfg.skills()) chosen.add(idx);
            }
            for (int idx : chosen)
                g.addTarget(new Target(roleList.get(ri).id(), skillIds.get(idx), 2 + pick(rng, 30, 35, 25, 10),
                        LocalDate.of(2026, 1, 1), null));
        }

        // courses (primary skill = index mod skills, so every skill has at least one course when courses >= skills)
        for (int i = 0; i < cfg.courses(); i++) {
            String cid = id("CRS", i + 1);
            int level = 1 + pick(rng, 25, 30, 25, 15, 5);
            int cost = (int) Math.round((150 + 9000 * Math.pow(rng.nextDouble(), 3)) / 5.0) * 5;
            int hours = 4 + rng.nextInt(77), capacity = 8 + rng.nextInt(33);
            double u = rng.nextDouble();
            String status = "ACTIVE";
            LocalDate from, until;
            if (u < 0.03) { status = "DISABLED"; from = LocalDate.of(2026, 1, 1).plusDays(rng.nextInt(90)); until = LocalDate.of(2027, 6, 30).plusDays(rng.nextInt(180)); }
            else if (u < 0.10) { from = LocalDate.of(2026, 1, 1).plusDays(rng.nextInt(60)); until = LocalDate.of(2026, 5, 1).plusDays(rng.nextInt(168)); }
            else { from = LocalDate.of(2026, 1, 1).plusDays(rng.nextInt(90)); until = LocalDate.of(2027, 6, 30).plusDays(rng.nextInt(180)); }
            int primary = i % cfg.skills();
            g.courses.put(cid, new Course(cid, LEVELS[level] + " " + g.skills.get(skillIds.get(primary)).name(), cost, hours, capacity, level, status, from, until));
            Set<Integer> sk = new LinkedHashSet<>();
            sk.add(primary);
            int extras = pick(rng, 40, 35, 25);
            for (int k = 0; k < extras; k++) {
                int idx = rng.nextInt(100) < 70 ? (primary % 10) + 10 * rng.nextInt(Math.max(1, cfg.skills() / 10)) : rng.nextInt(cfg.skills());
                if (idx < cfg.skills()) sk.add(idx);
            }
            for (int idx : sk) g.addCourseSkill(new CourseSkill(cid, skillIds.get(idx)));
        }
        // prerequisites: lower-level course that teaches the same primary skill => acyclic by construction
        for (int i = 0; i < cfg.courses(); i++) {
            String cid = id("CRS", i + 1);
            Course c = g.courses.get(cid);
            if (c.level() < 2 || rng.nextInt(100) >= 45) continue;
            List<String> cands = new ArrayList<>();
            for (String other : g.coursesBySkill.getOrDefault(g.skillsByCourse.get(cid).get(0), List.of()))
                if (!other.equals(cid) && g.courses.get(other).level() < c.level()) cands.add(other);
            Collections.shuffle(cands, rng);
            int n = Math.min(cands.size(), rng.nextInt(100) < 70 ? 1 : 2);
            for (int k = 0; k < n; k++) g.addPrereq(new Prereq(cid, cands.get(k)));
        }

        // initial proficiency assessments (history starts here)
        List<Assessment> asm = new ArrayList<>();
        int asmCounter = 0;
        for (Employee e : g.employees.values()) {
            Set<String> have = new HashSet<>();
            for (Target t : g.targetByRole.get(e.roleId()).values()) {
                if (rng.nextInt(100) >= 90) continue;
                int level = t.target() - pick(rng, 35, 30, 20, 10, 5);
                if (level < 1) continue;
                have.add(t.skillId());
                Assessment a = new Assessment(id("ASM", ++asmCounter), e.id(), t.skillId(), level, null,
                        LocalDate.of(2025, 11, 1).plusDays(rng.nextInt(71)), "INITIAL", null);
                asm.add(a); g.addAssessment(a);
            }
            for (int k = 0; k < 3; k++) {
                String sk = skillIds.get(rng.nextInt(skillIds.size()));
                int level = 1 + rng.nextInt(5);
                LocalDate dt = LocalDate.of(2025, 11, 1).plusDays(rng.nextInt(71));
                if (have.add(sk)) { Assessment a = new Assessment(id("ASM", ++asmCounter), e.id(), sk, level, null, dt, "INITIAL", null); asm.add(a); g.addAssessment(a); }
            }
        }

        // ---------- 2. valid baseline enrollment timeline ----------
        List<Employee> emps = new ArrayList<>(g.employees.values());
        int n = emps.size();
        LocalDate[] cursor = new LocalDate[n];
        int[] misses = new int[n];
        boolean[] done = new boolean[n];
        for (int i = 0; i < n; i++) cursor[i] = LocalDate.of(2026, 1, 15).plusDays(rng.nextInt(250));
        List<Event> events = new ArrayList<>();
        List<Completion> comps = new ArrayList<>();
        List<Assessment> reassess = new ArrayList<>();
        int count = 0;
        for (int round = 0; round < 80 && count < cfg.enrollments(); round++) {
            for (int i = 0; i < n && count < cfg.enrollments(); i++) {
                if (done[i]) continue;
                Employee e = emps.get(i);
                LocalDate date = cursor[i];
                if (date.isAfter(T)) { done[i] = true; continue; }
                int lag = 7 + rng.nextInt(8);
                List<String> gaps = new ArrayList<>();
                for (Target t : g.targetByRole.get(e.roleId()).values())
                    if (!t.effFrom().isAfter(date) && g.currentLevel(e.id(), t.skillId(), date) < t.target()) gaps.add(t.skillId());
                Collections.shuffle(gaps, rng);
                String chosenCourse = null;
                for (int a = 0; a < Math.min(6, gaps.size()) && chosenCourse == null; a++) {
                    List<String> cs = new ArrayList<>(g.coursesBySkill.getOrDefault(gaps.get(a), List.of()));
                    Collections.shuffle(cs, rng);
                    for (String cid : cs) {
                        Course c = g.courses.get(cid);
                        LocalDate st = date.plusDays(lag), en = st.plusDays(RecommendationRules.durationDays(c) - 1);
                        if (RecommendationRules.evaluate(g, e.id(), gaps.get(a), cid, date, st, en, null) == Decision.ACCEPT) { chosenCourse = cid; break; }
                    }
                }
                if (chosenCourse == null) {
                    cursor[i] = date.plusDays(30);
                    if (++misses[i] > 6) done[i] = true;
                    continue;
                }
                Course c = g.courses.get(chosenCourse);
                LocalDate start = date.plusDays(lag), end = start.plusDays(RecommendationRules.durationDays(c) - 1);
                String enrId = "ENR-" + e.id() + "-" + chosenCourse;
                boolean cancel = rng.nextInt(100) < 15;
                String status;
                events.add(new Event("", enrId, "ENROLLED", date));
                if (cancel) {
                    status = "CANCELLED";
                    int maxOff = (int) Math.max(0, Math.min(java.time.temporal.ChronoUnit.DAYS.between(date, start) - 1,
                            java.time.temporal.ChronoUnit.DAYS.between(date, T)));
                    LocalDate cd = date.plusDays(rng.nextInt((int) maxOff + 1));
                    events.add(new Event("", enrId, "CANCELLED", cd));
                    cursor[i] = cd.plusDays(3 + rng.nextInt(8));
                } else {
                    cursor[i] = end.plusDays(15 + rng.nextInt(26));
                    if (start.isAfter(T)) status = "ENROLLED";
                    else if (end.isAfter(T)) { status = "IN_PROGRESS"; events.add(new Event("", enrId, "STARTED", start)); }
                    else {
                        status = "COMPLETED";
                        events.add(new Event("", enrId, "STARTED", start));
                        events.add(new Event("", enrId, "COMPLETED", end));
                        boolean pass = rng.nextInt(100) < 88;
                        int score = pass ? PASS_SCORE + rng.nextInt(41) : 30 + rng.nextInt(PASS_SCORE - 30);
                        comps.add(new Completion("", enrId, e.id(), chosenCourse, end, score, score >= PASS_SCORE));
                        if (pass && !end.isAfter(T.minusDays(REASSESS_WINDOW_DAYS))) {
                            int gain = score >= 85 ? 2 : 1;
                            boolean noImprovement = rng.nextInt(100) < 10;
                            LocalDate ad = end.plusDays(1 + rng.nextInt(REASSESS_WINDOW_DAYS));
                            for (String sk : g.skillsByCourse.get(chosenCourse)) {
                                int cur = g.currentLevel(e.id(), sk, end);
                                int nl = (!noImprovement && c.level() > cur) ? Math.min(5, Math.min(c.level(), cur + gain)) : cur;
                                Assessment a = new Assessment("", e.id(), sk, nl, cur, ad, "REASSESSMENT", enrId);
                                reassess.add(a); g.addAssessment(a);
                            }
                        }
                    }
                }
                g.addEnrollment(new Enrollment(enrId, e.id(), chosenCourse, date, start, end, status));
                count++;
            }
        }
        if (count < cfg.enrollments())
            out.notes.add("WARNING: generated " + count + " enrollments, requested " + cfg.enrollments() + " (rules/timeline exhausted)");

        // ---------- 3. labelled edge cases that modify transactions ----------
        int N = Math.max(3, cfg.employees() / 200);
        Map<String, Enrollment> latestByEmp = new HashMap<>();
        for (Enrollment en : g.enrollments.values())
            latestByEmp.merge(en.empId(), en, (a, b) -> b.enrolled().isAfter(a.enrolled()) ? b : a);
        Map<String, Completion> compByEnr = new HashMap<>();
        for (Completion c : comps) compByEnr.putIfAbsent(c.enrollmentId(), c);

        // GAP_WITHOUT_REASSESSMENT: remove the reassessment of a latest-enrollment completion whose gap stays open
        List<Completion> shuffledComps = new ArrayList<>(comps);
        Collections.shuffle(shuffledComps, rng);
        List<String[]> gapCases = new ArrayList<>();   // enrollmentId, skillId, prev, target
        Set<String> usedEmp = new HashSet<>();
        for (Completion c : shuffledComps) {
            if (gapCases.size() >= N) break;
            if (!c.passed() || usedEmp.contains(c.empId()) || !latestByEmp.get(c.empId()).id().equals(c.enrollmentId())) continue;
            Assessment hit = null;
            Employee e = g.employees.get(c.empId());
            for (Assessment a : reassess) {
                if (!c.enrollmentId().equals(a.enrollmentId())) continue;
                Target t = g.targetByRole.get(e.roleId()).get(a.skillId());
                if (t != null && a.prev() < t.target()) { hit = a; break; }
            }
            if (hit == null) continue;
            usedEmp.add(c.empId());
            gapCases.add(new String[]{c.enrollmentId(), hit.skillId(), String.valueOf(hit.prev()),
                    String.valueOf(g.targetByRole.get(e.roleId()).get(hit.skillId()).target())});
        }
        Set<String> removed = new HashSet<>();
        for (String[] gc : gapCases) removed.add(gc[0]);
        reassess.removeIf(a -> removed.contains(a.enrollmentId()));

        // DUPLICATE_COMPLETION: second COMPLETED event + second completion row for the same enrollment
        List<Completion> dupSource = new ArrayList<>();
        for (Completion c : shuffledComps) { if (dupSource.size() >= N) break; if (!removed.contains(c.enrollmentId())) dupSource.add(c); }

        // ---------- 4. assign sequential IDs, build the final world ----------
        reassess.sort(Comparator.comparing(Assessment::date).thenComparing(Assessment::empId).thenComparing(Assessment::skillId));
        List<Assessment> finalAsm = new ArrayList<>(asm);
        for (Assessment a : reassess) finalAsm.add(a.withId(id("ASM", ++asmCounter)));
        events.sort(Comparator.comparing(Event::date).thenComparing(Event::enrollmentId).thenComparingInt(Event::order));
        comps.sort(Comparator.comparing(Completion::date).thenComparing(Completion::enrollmentId));
        List<Event> finalEv = new ArrayList<>();
        int evN = 0;
        for (Event ev : events) finalEv.add(new Event(id("EVT", ++evN), ev.enrollmentId(), ev.type(), ev.date()));
        List<Completion> finalComp = new ArrayList<>();
        int cmpN = 0;
        Map<String, String> firstCmpId = new HashMap<>();
        String yr = String.valueOf(T.getYear());
        for (Completion c : comps) {
            String cid = "CMP-" + yr + "-" + String.format("%06d", ++cmpN);
            firstCmpId.put(c.enrollmentId(), cid);
            finalComp.add(new Completion(cid, c.enrollmentId(), c.empId(), c.courseId(), c.date(), c.score(), c.passed()));
        }
        List<String[]> dupInfo = new ArrayList<>();  // second cmp id, first cmp id, enrollment id, emp, course
        for (Completion c : dupSource) {
            String cid = "CMP-" + yr + "-" + String.format("%06d", ++cmpN);
            LocalDate dd = c.date().plusDays(rng.nextInt(3));
            finalComp.add(new Completion(cid, c.enrollmentId(), c.empId(), c.courseId(), dd, c.score(), c.passed()));
            finalEv.add(new Event(id("EVT", ++evN), c.enrollmentId(), "COMPLETED", dd));
            dupInfo.add(new String[]{cid, firstCmpId.get(c.enrollmentId()), c.enrollmentId(), c.empId(), c.courseId()});
        }
        World f = new World();
        f.copyMasterFrom(g);
        g.enrollments.values().forEach(f::addEnrollment);
        f.events.addAll(finalEv);
        finalComp.forEach(f::addCompletion);
        finalAsm.forEach(f::addAssessment);
        out.world = f;

        // ---------- 5. candidate recommendations (+ injected rule-violation cases) ----------
        record Pending(String emp, String skill, String course, String issue, String expected, String related, String note) {}
        List<Pending> pend = new ArrayList<>();
        int baseWanted = Math.max(5, cfg.employees() / 5);
        for (Employee e : emps) {
            if (pend.size() >= baseWanted) break;
            List<Target> ts = new ArrayList<>(f.targetByRole.get(e.roleId()).values());
            ts.removeIf(t -> f.currentLevel(e.id(), t.skillId(), T) >= t.target());
            ts.sort(Comparator.comparingInt((Target t) -> -(t.target() - f.currentLevel(e.id(), t.skillId(), T))).thenComparing(Target::skillId));
            outer:
            for (Target t : ts) {
                String best = null;
                for (String cid : f.coursesBySkill.getOrDefault(t.skillId(), List.of())) {
                    if (RecommendationRules.evaluate(f, e.id(), t.skillId(), cid, T) != Decision.ACCEPT) continue;
                    if (best == null || f.courses.get(cid).cost() < f.courses.get(best).cost()) best = cid;
                }
                if (best != null) { pend.add(new Pending(e.id(), t.skillId(), best, "NONE", "ACCEPT", "", "feasible: all rules satisfied")); break outer; }
            }
        }
        Decision[] kinds = {Decision.REJECT_PREREQUISITE, Decision.REJECT_BUDGET, Decision.REJECT_UNAVAILABLE};
        List<Employee> shuffledEmps = new ArrayList<>(emps);
        Collections.shuffle(shuffledEmps, rng);
        for (Decision kind : kinds) {
            int found = 0;
            Set<String> used = new HashSet<>();
            for (Employee e : shuffledEmps) {
                if (found >= N) break;
                if (used.contains(e.id())) continue;
                List<Target> ts = new ArrayList<>(f.targetByRole.get(e.roleId()).values());
                Collections.shuffle(ts, rng);
                search:
                for (Target t : ts) {
                    if (f.currentLevel(e.id(), t.skillId(), T) >= t.target()) continue;
                    for (String cid : f.coursesBySkill.getOrDefault(t.skillId(), List.of())) {
                        // exactly one rule violated: it fails normally and is ACCEPT when that single check is skipped
                        if (RecommendationRules.evaluate(f, e.id(), t.skillId(), cid, T) != kind) continue;
                        if (RecommendationRules.evaluate(f, e.id(), t.skillId(), cid, T, kind) != Decision.ACCEPT) continue;
                        Course c = f.courses.get(cid);
                        String issue, expected, related = "", note;
                        if (kind == Decision.REJECT_PREREQUISITE) {
                            issue = "PREREQUISITE_VIOLATION"; expected = "REJECT_PREREQUISITE";
                            related = RecommendationRules.missingPrerequisite(f, e.id(), cid, T);
                            note = "prerequisite " + related + " not passed by " + T;
                        } else if (kind == Decision.REJECT_BUDGET) {
                            issue = "BUDGET_EXCEEDED"; expected = "REJECT_BUDGET";
                            note = "cost " + c.cost() + " > remaining budget " + f.remainingBudget(e.id());
                        } else {
                            issue = "COURSE_UNAVAILABLE"; expected = "REJECT_UNAVAILABLE";
                            note = "status=" + c.status() + " window " + c.from() + ".." + c.until() + " vs planning " + T;
                        }
                        pend.add(new Pending(e.id(), t.skillId(), cid, issue, expected, related, note));
                        used.add(e.id()); found++;
                        break search;
                    }
                }
            }
            if (found < N) out.notes.add("NOTE: only " + found + " of " + N + " " + kind + " cases could be constructed");
        }
        Collections.shuffle(pend, rng);   // mix labelled cases among valid candidates
        Map<String, String> recIdByKey = new HashMap<>();
        int recN = 0;
        for (Pending p : pend) {
            String rid = id("REC", ++recN);
            f.candidates.add(new Candidate(rid, p.emp(), p.skill(), p.course(), T));
            out.gtRecommendations.add(new String[]{rid, p.issue(), p.expected()});
            if (!p.issue().equals("NONE")) recIdByKey.put(p.issue() + "|" + p.emp() + "|" + p.course(), rid);
        }

        // ---------- 6. ground truth ----------
        int caseN = 0;
        List<String[]> edge = new ArrayList<>();
        for (String issue : new String[]{"PREREQUISITE_VIOLATION", "BUDGET_EXCEEDED"})
            for (Pending p : pend) if (p.issue().equals(issue))
                edge.add(new String[]{"", issue, p.emp(), p.course(), p.related(), p.expected(),
                        "recommendation_id=" + recIdByKey.get(issue + "|" + p.emp() + "|" + p.course()) + "; " + p.note()});
        for (String[] d : dupInfo)
            edge.add(new String[]{"", "DUPLICATE_COMPLETION", d[3], d[4], "", "REJECT_DUPLICATE_COMPLETION",
                    "completion_id=" + d[0] + " duplicates first completion " + d[1] + " for enrollment " + d[2]});
        for (String[] gc : gapCases) {
            Enrollment en = f.enrollments.get(gc[0]);
            edge.add(new String[]{"", "GAP_WITHOUT_REASSESSMENT", en.empId(), en.courseId(), "", "KEEP_GAP_OPEN",
                    "completion " + firstCmpId.get(gc[0]) + " has no reassessment; skill " + gc[1] + " previous_level=" + gc[2] + " target=" + gc[3]});
        }
        for (Pending p : pend) if (p.issue().equals("COURSE_UNAVAILABLE"))
            edge.add(new String[]{"", "COURSE_UNAVAILABLE", p.emp(), p.course(), "", p.expected(),
                    "recommendation_id=" + recIdByKey.get(p.issue() + "|" + p.emp() + "|" + p.course()) + "; " + p.note()});
        for (String[] r : edge) { r[0] = id("CASE", ++caseN); out.gtEdge.add(r); }

        for (Employee e : emps)
            for (Target t : f.targetByRole.get(e.roleId()).values()) {
                int cur = f.currentLevel(e.id(), t.skillId(), T);
                out.gtGaps.add(new String[]{e.id(), t.skillId(), String.valueOf(t.target()), String.valueOf(cur),
                        String.valueOf(Math.max(0, t.target() - cur))});
            }
        return out;
    }
}
