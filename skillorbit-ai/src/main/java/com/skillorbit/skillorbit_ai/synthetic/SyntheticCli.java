package com.skillorbit.skillorbit_ai.synthetic;

import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Config;
import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Generated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

/**
 * Command line entry point (plain Java, no Spring, no database).
 *   generate  --out DIR [--seed N --employees N --skills N --courses N --enrollments N --as-of YYYY-MM-DD | --starter]
 *   validate  --out DIR
 *   repro     --out DIR [same scale options]   (generates twice, compares row counts and SHA-256 of every file)
 *   selftest
 */
public final class SyntheticCli {
    private SyntheticCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); }
        String cmd = args[0];
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                if (k.equals("starter")) o.put(k, "true"); else o.put(k, args[++i]);
            }
        }
        Path out = Path.of(o.getOrDefault("out", "synthetic-output"));
        switch (cmd) {
            case "generate" -> {
                Config cfg = config(o);
                generate(cfg, out, "java -cp target/synthetic-classes " + SyntheticCli.class.getName() + " " + String.join(" ", args));
                DataValidator.Report r = DataValidator.validate(out);
                System.out.println(String.join("\n", r.lines));
                System.out.println("validation: " + r.passed + " passed, " + r.failed + " failed");
                System.exit(r.ok() ? 0 : 1);
            }
            case "validate" -> {
                DataValidator.Report r = DataValidator.validate(out);
                System.out.println(String.join("\n", r.lines));
                System.out.println("validation: " + r.passed + " passed, " + r.failed + " failed");
                System.exit(r.ok() ? 0 : 1);
            }
            case "repro" -> System.exit(repro(config(o), out) ? 0 : 1);
            case "selftest" -> {
                List<String> failures = SelfTest.run();
                failures.forEach(f -> System.out.println("FAIL " + f));
                System.out.println(failures.isEmpty() ? "SELFTEST PASSED" : "SELFTEST FAILED (" + failures.size() + ")");
                System.exit(failures.isEmpty() ? 0 : 1);
            }
            default -> { usage(); System.exit(2); }
        }
    }

    static Config config(Map<String, String> o) {
        Config base = o.containsKey("starter") ? Config.starter() : Config.full();
        return new Config(Long.parseLong(o.getOrDefault("seed", String.valueOf(base.seed()))),
                Integer.parseInt(o.getOrDefault("employees", String.valueOf(base.employees()))),
                Integer.parseInt(o.getOrDefault("skills", String.valueOf(base.skills()))),
                Integer.parseInt(o.getOrDefault("courses", String.valueOf(base.courses()))),
                Integer.parseInt(o.getOrDefault("enrollments", String.valueOf(base.enrollments()))),
                LocalDate.parse(o.getOrDefault("as-of", base.asOf().toString())));
    }

    public static Generated generate(Config cfg, Path out, String command) throws IOException {
        deleteTree(out);
        Generated g = DataGenerator.generate(cfg);
        Exporter.writeAll(g, out, command);
        g.notes.forEach(System.out::println);
        return g;
    }

    static boolean repro(Config cfg, Path out) throws IOException {
        Path a = out.resolve("run1"), b = out.resolve("run2");
        generate(cfg, a, "repro run 1");
        generate(cfg, b, "repro run 1");   // identical command text so MANIFEST.txt is comparable
        SortedMap<String, String> ca = Exporter.checksums(a), cb = Exporter.checksums(b);
        StringBuilder sb = new StringBuilder("Reproducibility check (same seed " + cfg.seed() + ", generated twice)\n");
        boolean same = ca.equals(cb);
        for (String k : ca.keySet()) sb.append(ca.get(k).equals(cb.get(k)) ? "MATCH     " : "DIFFERENT ").append(k).append("  ").append(ca.get(k), 0, 16).append("...\n");
        sb.append("files compared: ").append(ca.size()).append(" ; result: ").append(same ? "IDENTICAL" : "DIFFERENT").append('\n');
        Files.writeString(out.resolve("reproducibility_report.txt"), sb.toString());
        System.out.println(sb);
        return same;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) { for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(x); }
    }

    static void usage() {
        System.out.println("usage: generate|validate|repro|selftest [--out DIR] [--starter] [--seed N] [--employees N] [--skills N] [--courses N] [--enrollments N] [--as-of YYYY-MM-DD]");
    }
}
