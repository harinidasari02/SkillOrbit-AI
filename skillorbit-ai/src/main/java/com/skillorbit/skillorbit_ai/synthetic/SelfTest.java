package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Config;
import com.skillorbit.skillorbit_ai.synthetic.Model.*;
import com.skillorbit.skillorbit_ai.synthetic.RecommendationRules.Decision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Framework-free tests (also invoked from JUnit). Returns the list of failures; empty means pass. */
public final class SelfTest {
    private SelfTest() {}

    static void eq(List<String> f, String name, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) f.add(name + ": expected " + expected + " but was " + actual);
    }

    public static List<String> run() throws Exception {
        List<String> f = new ArrayList<>();
        ruleUnitTests(f);
        Path tmp = Files.createTempDirectory("skillorbit-selftest");
        try {
            Config cfg = Config.starter();
            Path a = tmp.resolve("a"), b = tmp.resolve("b");
            SyntheticCli.generate(cfg, a, "selftest");
            SyntheticCli.generate(cfg, b, "selftest");
            DataValidator.Report r = DataValidator.validate(a);
            if (!r.ok()) r.lines.stream().filter(l -> l.startsWith("FAIL")).forEach(l -> f.add("validator on clean data: " + l));
            eq(f, "reproducible checksums", Exporter.checksums(a), Exporter.checksums(b));
            Config other = new Config(cfg.seed() + 1, cfg.employees(), cfg.skills(), cfg.courses(), cfg.enrollments(), cfg.asOf());
            Path c = tmp.resolve("c");
            SyntheticCli.generate(other, c, "selftest");
            if (Exporter.checksums(a).equals(Exporter.checksums(c))) f.add("different seed produced identical data");
            eq(f, "enrollment count", cfg.enrollments(), DataGenerator.generate(cfg).world.enrollments.size());
            corruptionTests(f, a, tmp);
        } finally { SyntheticCli.deleteTree(tmp); }
        return f;
    }

    /** The validator must fail when data is deliberately broken (proves it is not vacuous). */
    static void corruptionTests(List<String> f, Path clean, Path tmp) throws Exception {
        String[][] mutations = {
            {"duplicate employee PK", "csv/employees.csv", "dupLine"},
            {"orphan FK in enrollments", "csv/enrollments.csv", "orphan"},
            {"unlabelled duplicate completion", "csv/completions.csv", "dupCompletion"},
            {"budget_summary tampered", "derived/budget_summary.csv", "tamper"},
            {"ground truth leaked into input", "csv/courses.csv", "leak"}};
        for (String[] m : mutations) {
            Path d = tmp.resolve("m-" + Math.abs(m[0].hashCode()));
            copyTree(clean, d);
            Path file = d.resolve(m[1]);
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            switch (m[2]) {
                case "dupLine" -> lines.add(lines.get(1));
                case "orphan" -> { String[] p = lines.get(1).split(",", -1); p[2] = "CRS-999999"; lines.set(1, String.join(",", p)); }
                case "dupCompletion" -> {
                    String[] p = lines.get(1).split(",", -1); p[0] = "CMP-2026-999999"; lines.add(String.join(",", p));
                }
                case "tamper" -> { String[] p = lines.get(1).split(",", -1); p[3] = "1"; lines.set(1, String.join(",", p)); }
                case "leak" -> lines.set(0, lines.get(0) + ",issue_type");
            }
            if (m[2].equals("leak")) for (int i = 1; i < lines.size(); i++) lines.set(i, lines.get(i) + ",NONE");
            Files.write(file, lines);
            DataValidator.Report r = DataValidator.validate(d);
            if (r.ok()) f.add("validator failed to detect: " + m[0]);
        }
    }

    static void copyTree(Path from, Path to) throws Exception {
        try (var s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else Files.copy(p, t);
            }
        }
    }

    /** Hand-built mini world exercising each rule in isolation (normal, boundary, failure). */
    static void ruleUnitTests(List<String> f) {
        LocalDate T = LocalDate.of(2026, 10, 28);
        World w = new World();
        w.roles.put("ROL-000001", new Role("ROL-000001", "Dev", "ENG"));
        w.skills.put("SKL-000001", new Skill("SKL-000001", "Java", "Java"));
        w.employees.put("EMP-000001", new Employee("EMP-000001", "A B", "a@example.com", "ROL-000001", "ENG", LocalDate.of(2020, 1, 1), "ACTIVE"));
        w.budgets.put("ENG", new Budget("BUD-ENG-2026", "ENG", 2026, 100000, 5000));
        w.addTarget(new Target("ROL-000001", "SKL-000001", 4, LocalDate.of(2026, 1, 1), null));
        w.courses.put("CRS-000001", new Course("CRS-000001", "Intro", 1000, 10, 2, 2, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        w.courses.put("CRS-000002", new Course("CRS-000002", "Adv", 1000, 10, 2, 4, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        w.courses.put("CRS-000003", new Course("CRS-000003", "Pricey", 5001, 10, 2, 4, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        w.courses.put("CRS-000004", new Course("CRS-000004", "Off", 10, 10, 2, 4, "DISABLED", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        w.courses.put("CRS-000005", new Course("CRS-000005", "Expired", 10, 10, 2, 4, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 27)));
        w.courses.put("CRS-000006", new Course("CRS-000006", "Low", 10, 10, 2, 1, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        for (String c : w.courses.keySet()) w.addCourseSkill(new CourseSkill(c, "SKL-000001"));
        w.addPrereq(new Prereq("CRS-000002", "CRS-000001"));
        String e = "EMP-000001", s = "SKL-000001";
        eq(f, "prerequisite not met", Decision.REJECT_PREREQUISITE, RecommendationRules.evaluate(w, e, s, "CRS-000002", T));
        eq(f, "no prerequisite -> accept", Decision.ACCEPT, RecommendationRules.evaluate(w, e, s, "CRS-000001", T));
        eq(f, "budget exceeded", Decision.REJECT_BUDGET, RecommendationRules.evaluate(w, e, s, "CRS-000003", T));
        eq(f, "budget boundary: cost == remaining is allowed", 5000L, w.remainingBudget(e));
        eq(f, "disabled course", Decision.REJECT_UNAVAILABLE, RecommendationRules.evaluate(w, e, s, "CRS-000004", T));
        eq(f, "expired window (day before planning date)", Decision.REJECT_UNAVAILABLE, RecommendationRules.evaluate(w, e, s, "CRS-000005", T));
        eq(f, "boundary: last day of window is available", true, RecommendationRules.isAvailable(w.courses.get("CRS-000005"), LocalDate.of(2026, 10, 27)));
        eq(f, "boundary: day after window is unavailable", false, RecommendationRules.isAvailable(w.courses.get("CRS-000005"), LocalDate.of(2026, 10, 28)));
        eq(f, "low-level course still raises a zero level -> accept", Decision.ACCEPT, RecommendationRules.evaluate(w, e, s, "CRS-000006", T));
        // attendance != learning: passed completion without reassessment leaves the gap and prerequisites logic untouched
        w.addEnrollment(new Enrollment("ENR-EMP-000001-CRS-000001", e, "CRS-000001", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 12), "COMPLETED"));
        w.addCompletion(new Completion("CMP-2026-000001", "ENR-EMP-000001-CRS-000001", e, "CRS-000001", LocalDate.of(2026, 3, 12), 90, true));
        eq(f, "completion alone does not change proficiency", 0, w.currentLevel(e, s, T));
        eq(f, "after passing prerequisite the advanced course is eligible", Decision.ACCEPT, RecommendationRules.evaluate(w, e, s, "CRS-000002", T));
        eq(f, "already enrolled course rejected", Decision.REJECT_ALREADY_ENROLLED, RecommendationRules.evaluate(w, e, s, "CRS-000001", T));
        w.addCompletion(new Completion("CMP-2026-000002", "ENR-EMP-000001-CRS-000001", e, "CRS-000001", LocalDate.of(2026, 3, 13), 90, true));
        eq(f, "duplicate completion keeps first date", LocalDate.of(2026, 3, 12), w.passedOn(e, "CRS-000001"));
        w.addAssessment(new Assessment("ASM-000001", e, s, 4, 0, LocalDate.of(2026, 3, 20), "REASSESSMENT", "ENR-EMP-000001-CRS-000001"));
        eq(f, "reassessment raises proficiency", 4, w.currentLevel(e, s, T));
        eq(f, "gap closed -> nothing left to recommend", Decision.REJECT_NO_GAP_CLOSURE, RecommendationRules.evaluate(w, e, s, "CRS-000002", T));
    }
}
