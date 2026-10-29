package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Generated;
import com.skillorbit.skillorbit_ai.synthetic.Model.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** Writes CSV, JSON and SQL (schema + seed) plus a checksum manifest. */
public final class Exporter {
    private Exporter() {}

    public record Tbl(String name, String[] header, List<String[]> rows) {}

    static final Set<String> NUMERIC = Set.of("target_proficiency", "cost", "duration_hours", "capacity", "level_taught",
            "fiscal_year", "allocated_amount", "per_employee_cap", "score", "passed", "proficiency_level", "previous_level",
            "committed_amount", "remaining_amount");

    static <T> List<String[]> map(Collection<T> c, java.util.function.Function<T, String[]> f) {
        List<String[]> l = new ArrayList<>(c.size());
        for (T t : c) l.add(f.apply(t));
        return l;
    }

    public static List<Tbl> core(World w) {
        return List.of(
            new Tbl("roles", Role.H, map(w.roles.values(), Role::row)),
            new Tbl("skills", Skill.H, map(w.skills.values(), Skill::row)),
            new Tbl("employees", Employee.H, map(w.employees.values(), Employee::row)),
            new Tbl("role_skill_targets", Target.H, map(w.targets, Target::row)),
            new Tbl("courses", Course.H, map(w.courses.values(), Course::row)),
            new Tbl("course_skills", CourseSkill.H, courseSkillRows(w)),
            new Tbl("course_prerequisites", Prereq.H, prereqRows(w)),
            new Tbl("budgets", Budget.H, map(w.budgets.values(), Budget::row)),
            new Tbl("enrollments", Enrollment.H, map(w.enrollments.values(), Enrollment::row)),
            new Tbl("enrollment_events", Event.H, map(w.events, Event::row)),
            new Tbl("completions", Completion.H, map(w.completions, Completion::row)),
            new Tbl("employee_skill_assessments", Assessment.H, map(w.assessments, Assessment::row)),
            new Tbl("candidate_recommendations", Candidate.H, map(w.candidates, Candidate::row)));
    }

    static List<String[]> courseSkillRows(World w) {
        List<String[]> l = new ArrayList<>();
        w.skillsByCourse.forEach((c, ss) -> ss.forEach(s -> l.add(new String[]{c, s})));
        return l;
    }

    static List<String[]> prereqRows(World w) {
        List<String[]> l = new ArrayList<>();
        w.prereqs.forEach((c, ps) -> ps.forEach(p -> l.add(new String[]{c, p})));
        return l;
    }

    /** Derived from enrollments and budgets only; never generated independently. */
    public static Tbl budgetSummary(World w) {
        List<String[]> l = new ArrayList<>();
        for (Budget b : w.budgets.values()) {
            long committed = w.committedByDept(b.dept());
            l.add(new String[]{b.id(), b.dept(), String.valueOf(b.allocated()), String.valueOf(committed), String.valueOf(b.allocated() - committed)});
        }
        return new Tbl("budget_summary", new String[]{"budget_id", "department", "allocated_amount", "committed_amount", "remaining_amount"}, l);
    }

    public static List<Tbl> ground(Generated g) {
        return List.of(
            new Tbl("edge_case_ground_truth", new String[]{"case_id", "issue_type", "employee_id", "course_id", "related_course_id", "expected_decision", "evidence_note"}, g.gtEdge),
            new Tbl("recommendation_decisions", new String[]{"recommendation_id", "issue_type", "expected_decision"}, g.gtRecommendations),
            new Tbl("skill_gaps", new String[]{"employee_id", "skill_id", "target_proficiency", "current_proficiency", "skill_gap"}, g.gtGaps));
    }

    public static void writeAll(Generated g, Path out, String command) throws IOException {
        Files.createDirectories(out);
        List<Tbl> core = core(g.world);
        for (Tbl t : core) {
            Csv.write(out.resolve("csv").resolve(t.name() + ".csv"), t.header(), t.rows());
            writeJson(out.resolve("json").resolve(t.name() + ".json"), t);
        }
        Tbl bs = budgetSummary(g.world);
        Csv.write(out.resolve("derived").resolve("budget_summary.csv"), bs.header(), bs.rows());
        for (Tbl t : ground(g)) {
            Csv.write(out.resolve("ground_truth").resolve(t.name() + ".csv"), t.header(), t.rows());
            writeJson(out.resolve("ground_truth").resolve(t.name() + ".json"), t);
        }
        Files.writeString(out.resolve("metrics.json"), Metrics.toJson(Metrics.compute(g.world, g.gtRecommendations, g.cfg.asOf()), 0), StandardCharsets.UTF_8);
        Files.createDirectories(out.resolve("sql"));
        Files.writeString(out.resolve("sql").resolve("schema.sql"), SCHEMA, StandardCharsets.UTF_8);
        writeSql(out.resolve("sql").resolve("seed_data.sql"), core);
        writeManifest(g, out, command, core, bs);
    }

    static void writeJson(Path p, Tbl t) throws IOException {
        Files.createDirectories(p.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("[\n");
            for (int r = 0; r < t.rows().size(); r++) {
                String[] row = t.rows().get(r);
                StringBuilder sb = new StringBuilder("  {");
                for (int c = 0; c < t.header().length; c++) {
                    if (c > 0) sb.append(", ");
                    sb.append('"').append(t.header()[c]).append("\": ");
                    String v = c < row.length ? row[c] : "";
                    if (v.isEmpty()) sb.append("null");
                    else if (NUMERIC.contains(t.header()[c])) sb.append(v);
                    else sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                }
                sb.append(r < t.rows().size() - 1 ? "},\n" : "}\n");
                w.write(sb.toString());
            }
            w.write("]\n");
        }
    }

    static void writeSql(Path p, List<Tbl> tables) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write("-- SkillOrbit synthetic seed data (generated; do not edit). Load sql/schema.sql first.\nUSE skillorbit_synthetic;\n");
            for (Tbl t : tables) {
                List<String[]> rows = t.rows();
                for (int i = 0; i < rows.size(); i += 500) {
                    w.write("INSERT INTO " + t.name() + " (" + String.join(", ", t.header()) + ") VALUES\n");
                    int end = Math.min(rows.size(), i + 500);
                    for (int r = i; r < end; r++) {
                        StringBuilder sb = new StringBuilder("(");
                        for (int c = 0; c < t.header().length; c++) {
                            if (c > 0) sb.append(", ");
                            String v = rows.get(r)[c];
                            if (v.isEmpty()) sb.append("NULL");
                            else if (NUMERIC.contains(t.header()[c])) sb.append(v);
                            else sb.append('\'').append(v.replace("\\", "\\\\").replace("'", "''")).append('\'');
                        }
                        sb.append(r < end - 1 ? "),\n" : ");\n");
                        w.write(sb.toString());
                    }
                }
            }
        }
    }

    static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** relative path -> sha256 for every generated file (excludes the manifest and validation report). */
    public static SortedMap<String, String> checksums(Path out) throws IOException {
        SortedMap<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(out)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                String rel = out.relativize(p).toString().replace('\\', '/');
                if (rel.equals("MANIFEST.txt") || rel.equals("validation_report.txt") || rel.equals("reproducibility_report.txt")) continue;
                m.put(rel, sha256(p));
            }
        }
        return m;
    }

    static void writeManifest(Generated g, Path out, String command, List<Tbl> core, Tbl bs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("SkillOrbit synthetic dataset manifest\n");
        sb.append("generator_version=").append(DataGenerator.VERSION).append('\n');
        sb.append("seed=").append(g.cfg.seed()).append('\n');
        sb.append("as_of_date=").append(g.cfg.asOf()).append('\n');
        sb.append("employees=").append(g.cfg.employees()).append(" skills=").append(g.cfg.skills())
          .append(" courses=").append(g.cfg.courses()).append(" enrollments=").append(g.cfg.enrollments()).append('\n');
        sb.append("command=").append(command).append('\n');
        sb.append("external_data_used=none (fully synthetic; no third-party sources, no personal data)\n");
        for (String n : g.notes) sb.append(n).append('\n');
        sb.append("\n# table\trows\n");
        for (Tbl t : core) sb.append(t.name()).append('\t').append(t.rows().size()).append('\n');
        sb.append(bs.name()).append('\t').append(bs.rows().size()).append('\n');
        for (Tbl t : ground(g)) sb.append("ground_truth/").append(t.name()).append('\t').append(t.rows().size()).append('\n');
        sb.append("\n# sha256\tfile\n");
        checksums(out).forEach((k, v) -> sb.append(v).append('\t').append(k).append('\n'));
        Files.writeString(out.resolve("MANIFEST.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    static final String SCHEMA = """
        -- SkillOrbit synthetic schema (MySQL 8). Own database so it never collides with other schemas.
        CREATE DATABASE IF NOT EXISTS skillorbit_synthetic;
        USE skillorbit_synthetic;
        CREATE TABLE roles (role_id VARCHAR(10) PRIMARY KEY, role_name VARCHAR(60) NOT NULL, department VARCHAR(10) NOT NULL);
        CREATE TABLE skills (skill_id VARCHAR(10) PRIMARY KEY, skill_name VARCHAR(120) NOT NULL UNIQUE, category VARCHAR(30) NOT NULL);
        CREATE TABLE employees (employee_id VARCHAR(10) PRIMARY KEY, full_name VARCHAR(80) NOT NULL, email VARCHAR(120) NOT NULL UNIQUE,
          role_id VARCHAR(10) NOT NULL, department VARCHAR(10) NOT NULL, hire_date DATE NOT NULL, status VARCHAR(12) NOT NULL,
          FOREIGN KEY (role_id) REFERENCES roles(role_id));
        CREATE TABLE role_skill_targets (role_id VARCHAR(10), skill_id VARCHAR(10), target_proficiency TINYINT NOT NULL CHECK (target_proficiency BETWEEN 1 AND 5),
          effective_from DATE NOT NULL, effective_to DATE NULL, PRIMARY KEY (role_id, skill_id, effective_from),
          FOREIGN KEY (role_id) REFERENCES roles(role_id), FOREIGN KEY (skill_id) REFERENCES skills(skill_id));
        CREATE TABLE courses (course_id VARCHAR(10) PRIMARY KEY, course_name VARCHAR(160) NOT NULL, cost INT NOT NULL CHECK (cost > 0),
          duration_hours INT NOT NULL CHECK (duration_hours > 0), capacity INT NOT NULL CHECK (capacity > 0),
          level_taught TINYINT NOT NULL CHECK (level_taught BETWEEN 1 AND 5), status VARCHAR(10) NOT NULL,
          available_from DATE NOT NULL, available_until DATE NOT NULL, CHECK (available_until >= available_from));
        CREATE TABLE course_skills (course_id VARCHAR(10), skill_id VARCHAR(10), PRIMARY KEY (course_id, skill_id),
          FOREIGN KEY (course_id) REFERENCES courses(course_id), FOREIGN KEY (skill_id) REFERENCES skills(skill_id));
        CREATE TABLE course_prerequisites (course_id VARCHAR(10), prerequisite_course_id VARCHAR(10), PRIMARY KEY (course_id, prerequisite_course_id),
          CHECK (course_id <> prerequisite_course_id), FOREIGN KEY (course_id) REFERENCES courses(course_id),
          FOREIGN KEY (prerequisite_course_id) REFERENCES courses(course_id));
        CREATE TABLE budgets (budget_id VARCHAR(20) PRIMARY KEY, department VARCHAR(10) NOT NULL, fiscal_year INT NOT NULL,
          allocated_amount BIGINT NOT NULL, per_employee_cap BIGINT NOT NULL, UNIQUE (department, fiscal_year));
        CREATE TABLE enrollments (enrollment_id VARCHAR(30) PRIMARY KEY, employee_id VARCHAR(10) NOT NULL, course_id VARCHAR(10) NOT NULL,
          enrolled_date DATE NOT NULL, planned_start_date DATE NOT NULL, planned_end_date DATE NOT NULL, status VARCHAR(12) NOT NULL,
          UNIQUE (employee_id, course_id), FOREIGN KEY (employee_id) REFERENCES employees(employee_id), FOREIGN KEY (course_id) REFERENCES courses(course_id));
        CREATE TABLE enrollment_events (event_id VARCHAR(12) PRIMARY KEY, enrollment_id VARCHAR(30) NOT NULL, event_type VARCHAR(12) NOT NULL,
          event_date DATE NOT NULL, FOREIGN KEY (enrollment_id) REFERENCES enrollments(enrollment_id));
        CREATE TABLE completions (completion_id VARCHAR(20) PRIMARY KEY, enrollment_id VARCHAR(30) NOT NULL, employee_id VARCHAR(10) NOT NULL,
          course_id VARCHAR(10) NOT NULL, completion_date DATE NOT NULL, score INT NOT NULL CHECK (score BETWEEN 0 AND 100), passed BOOLEAN NOT NULL,
          FOREIGN KEY (enrollment_id) REFERENCES enrollments(enrollment_id), FOREIGN KEY (employee_id) REFERENCES employees(employee_id),
          FOREIGN KEY (course_id) REFERENCES courses(course_id));
        -- NOTE: no UNIQUE(enrollment_id) on completions on purpose: the DUPLICATE_COMPLETION fixtures must be loadable and detectable.
        CREATE TABLE employee_skill_assessments (assessment_id VARCHAR(12) PRIMARY KEY, employee_id VARCHAR(10) NOT NULL, skill_id VARCHAR(10) NOT NULL,
          proficiency_level TINYINT NOT NULL CHECK (proficiency_level BETWEEN 0 AND 5), previous_level TINYINT NULL, assessed_date DATE NOT NULL,
          source VARCHAR(12) NOT NULL, enrollment_id VARCHAR(30) NULL,
          FOREIGN KEY (employee_id) REFERENCES employees(employee_id), FOREIGN KEY (skill_id) REFERENCES skills(skill_id),
          FOREIGN KEY (enrollment_id) REFERENCES enrollments(enrollment_id));
        CREATE TABLE candidate_recommendations (recommendation_id VARCHAR(12) PRIMARY KEY, employee_id VARCHAR(10) NOT NULL, skill_id VARCHAR(10) NOT NULL,
          course_id VARCHAR(10) NOT NULL, planning_date DATE NOT NULL, FOREIGN KEY (employee_id) REFERENCES employees(employee_id),
          FOREIGN KEY (skill_id) REFERENCES skills(skill_id), FOREIGN KEY (course_id) REFERENCES courses(course_id));
        CREATE VIEW budget_summary AS
          SELECT b.budget_id, b.department, b.allocated_amount,
                 COALESCE(SUM(c.cost), 0) AS committed_amount, b.allocated_amount - COALESCE(SUM(c.cost), 0) AS remaining_amount
          FROM budgets b LEFT JOIN employees e ON e.department = b.department
          LEFT JOIN enrollments en ON en.employee_id = e.employee_id AND en.status <> 'CANCELLED'
          LEFT JOIN courses c ON c.course_id = en.course_id GROUP BY b.budget_id, b.department, b.allocated_amount;
        """;
}
