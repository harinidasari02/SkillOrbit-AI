package com.skillorbit.skillorbit_ai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/health")
    public String health() {
        return "SkillOrbit AI is running";
    }

    @GetMapping("/api/health")
    public String apiHealth() {
        return health();
    }
}