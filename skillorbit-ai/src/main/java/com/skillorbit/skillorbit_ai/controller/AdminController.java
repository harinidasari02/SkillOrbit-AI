package com.skillorbit.skillorbit_ai.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skillorbit.skillorbit_ai.service.DatasetService;

/** Dataset lifecycle: generate, validate, metrics, manifest. Reads real files, never hardcoded results. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DatasetService datasets;

    public AdminController(DatasetService datasets) {
        this.datasets = datasets;
    }

    @PostMapping("/generate-dataset")
    public Map<String, Object> generate(@RequestParam(defaultValue = "starter") String scale) {
        return datasets.generate(scale);
    }

    @GetMapping("/validation")
    public Map<String, Object> validation() {
        return datasets.validate();
    }

    @GetMapping(value = "/validation/report", produces = "text/plain")
    public String validationReport() {
        return datasets.read("validation_report.txt");
    }

    @GetMapping(value = "/metrics", produces = "application/json")
    public String metrics() {
        return datasets.read("metrics.json");
    }

    @GetMapping(value = "/manifest", produces = "text/plain")
    public String manifest() {
        return datasets.read("MANIFEST.txt");
    }
}
