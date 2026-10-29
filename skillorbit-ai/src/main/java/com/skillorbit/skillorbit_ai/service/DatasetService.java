package com.skillorbit.skillorbit_ai.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.skillorbit.skillorbit_ai.synthetic.DataGenerator.Config;
import com.skillorbit.skillorbit_ai.synthetic.DataValidator;
import com.skillorbit.skillorbit_ai.synthetic.SyntheticCli;
import com.skillorbit.skillorbit_ai.synthetic.World;

/** Owns the generated dataset: loads it into memory for the rule engine, regenerates and validates on demand. */
@Service
public class DatasetService {

    private final Path root;
    private final String activeScale;
    private World world;

    public DatasetService(@Value("${skillorbit.dataset.root:synthetic-output}") String root,
                          @Value("${skillorbit.dataset.scale:starter}") String activeScale) {
        this.root = Path.of(root);
        this.activeScale = activeScale;
    }

    public Path activeDir() { return root.resolve(activeScale); }

    public synchronized World world() {
        if (world == null) {
            try {
                world = World.load(activeDir().resolve("csv"), null);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No dataset in " + activeDir() + " - POST /api/admin/generate-dataset?scale=" + activeScale);
            }
        }
        return world;
    }

    /** Planning date = the dataset's fixed as-of date (never the wall clock), so results are reproducible. */
    public LocalDate asOf() {
        for (String line : readLines("MANIFEST.txt")) {
            if (line.startsWith("as_of_date=")) return LocalDate.parse(line.substring("as_of_date=".length()).trim());
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MANIFEST.txt has no as_of_date");
    }

    public synchronized Map<String, Object> generate(String scale) {
        Config cfg;
        if ("starter".equals(scale)) cfg = Config.starter();
        else if ("full".equals(scale)) cfg = Config.full();
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scale must be 'starter' or 'full'");
        Path out = root.resolve(scale);
        try {
            SyntheticCli.generate(cfg, out, "POST /api/admin/generate-dataset?scale=" + scale);
            DataValidator.Report report = DataValidator.validate(out);
            if (scale.equals(activeScale)) world = null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scale", scale);
            m.put("seed", cfg.seed());
            m.put("employees", cfg.employees());
            m.put("skills", cfg.skills());
            m.put("courses", cfg.courses());
            m.put("enrollments", cfg.enrollments());
            m.put("outputDirectory", out.toString());
            m.put("validationPassed", report.passed);
            m.put("validationFailed", report.failed);
            return m;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Generation failed: " + e.getMessage());
        }
    }

    public Map<String, Object> validate() {
        try {
            DataValidator.Report r = DataValidator.validate(activeDir());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", r.ok());
            m.put("passed", r.passed);
            m.put("failed", r.failed);
            m.put("lines", r.lines);
            return m;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No dataset in " + activeDir());
        }
    }

    public String read(String fileName) {
        try {
            return Files.readString(activeDir().resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, fileName + " not found in " + activeDir());
        }
    }

    private java.util.List<String> readLines(String fileName) {
        return java.util.Arrays.asList(read(fileName).split("\n"));
    }
}
