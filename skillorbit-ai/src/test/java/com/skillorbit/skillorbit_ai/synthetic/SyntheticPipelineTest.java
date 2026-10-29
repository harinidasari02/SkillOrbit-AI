package com.skillorbit.skillorbit_ai.synthetic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Config;
import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Generated;

/** Spring-free tests: no database, no network. */
class SyntheticPipelineTest {

    @Test
    void ruleUnitTestsAndValidatorMutationTestsHaveNoFailures() throws Exception {
        List<String> failures = SelfTest.run();
        assertTrue(failures.isEmpty(), "self-test failures: " + failures);
    }

    @Test
    void starterDatasetValidatesAndCoversAllFiveEdgeCases(@TempDir Path dir) throws Exception {
        Generated g = SyntheticCli.generate(Config.starter(), dir, "test");
        DataValidator.Report r = DataValidator.validate(dir);
        assertEquals(0, r.failed, String.join("\n", r.lines));
        assertEquals(600, g.world.enrollments.size());
        Set<String> types = new HashSet<>();
        g.gtEdge.forEach(row -> types.add(row[1]));
        assertEquals(Set.of("PREREQUISITE_VIOLATION", "BUDGET_EXCEEDED", "DUPLICATE_COMPLETION",
                "GAP_WITHOUT_REASSESSMENT", "COURSE_UNAVAILABLE"), types);
    }

    @Test
    void sameSeedGivesIdenticalFilesAndDifferentSeedDoesNot(@TempDir Path dir) throws Exception {
        Config c = Config.starter();
        SyntheticCli.generate(c, dir.resolve("a"), "test");
        SyntheticCli.generate(c, dir.resolve("b"), "test");
        SyntheticCli.generate(new Config(c.seed() + 1, c.employees(), c.skills(), c.courses(), c.enrollments(), c.asOf()),
                dir.resolve("c"), "test");
        assertEquals(Exporter.checksums(dir.resolve("a")), Exporter.checksums(dir.resolve("b")));
        assertFalse(Exporter.checksums(dir.resolve("a")).equals(Exporter.checksums(dir.resolve("c"))));
    }

    @Test
    void groundTruthIsNotLeakedIntoInputTables(@TempDir Path dir) throws Exception {
        SyntheticCli.generate(Config.starter(), dir, "test");
        for (String t : new String[]{"employees", "courses", "enrollments", "completions", "candidate_recommendations"}) {
            String header = java.nio.file.Files.readAllLines(dir.resolve("csv").resolve(t + ".csv")).get(0);
            assertFalse(header.contains("issue_type") || header.contains("expected_decision"), t + " leaks ground truth");
        }
    }
}
