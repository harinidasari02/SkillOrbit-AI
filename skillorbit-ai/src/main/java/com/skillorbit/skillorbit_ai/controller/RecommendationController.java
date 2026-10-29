package com.skillorbit.skillorbit_ai.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.skillorbit.skillorbit_ai.service.DatasetService;
import com.skillorbit.skillorbit_ai.service.OllamaService;
import com.skillorbit.skillorbit_ai.synthetic.Model.Course;
import com.skillorbit.skillorbit_ai.synthetic.Model.Employee;
import com.skillorbit.skillorbit_ai.synthetic.Model.Target;
import com.skillorbit.skillorbit_ai.synthetic.RecommendationRules;
import com.skillorbit.skillorbit_ai.synthetic.RecommendationRules.Decision;
import com.skillorbit.skillorbit_ai.synthetic.World;

/**
 * Rule-based skill-gap analysis and course recommendations. Read-only: GET never writes state.
 * Every decision comes from {@link RecommendationRules}; Ollama only words it (see /explanation).
 */
@RestController
@RequestMapping("/api")
public class RecommendationController {

    public record EmployeeSummary(String employeeId, String fullName, String role, String department) {}

    public record SkillGap(String skillId, String skillName, int target, int current, int gap) {}

    public record CourseDecision(String courseId, String courseName, int cost, int levelTaught,
                                 String decision, String reason) {}

    public record GapRecommendation(String skillId, String skillName, int target, int current, int gap,
                                    List<CourseDecision> feasible, int rejectedCount,
                                    Map<String, Long> rejectedByReason) {}

    private final DatasetService datasets;
    private final OllamaService ollama;

    public RecommendationController(DatasetService datasets, OllamaService ollama) {
        this.datasets = datasets;
        this.ollama = ollama;
    }

    @GetMapping("/employees")
    public List<EmployeeSummary> employees(@RequestParam(defaultValue = "25") int limit) {
        World w = datasets.world();
        List<EmployeeSummary> out = new ArrayList<>();
        for (Employee e : w.employees.values()) {
            if (out.size() >= Math.max(1, Math.min(limit, 200))) break;
            out.add(new EmployeeSummary(e.id(), e.name(), w.roles.get(e.roleId()).name(), e.dept()));
        }
        return out;
    }

    @GetMapping("/employees/{employeeId}/skill-gaps")
    public List<SkillGap> skillGaps(@PathVariable String employeeId, @RequestParam(required = false) String date) {
        World w = datasets.world();
        Employee emp = employee(w, employeeId);
        LocalDate planning = planning(date);
        List<SkillGap> gaps = new ArrayList<>();
        for (Target t : w.targetByRole.getOrDefault(emp.roleId(), Map.of()).values()) {
            int current = w.currentLevel(employeeId, t.skillId(), planning);
            if (current < t.target()) {
                gaps.add(new SkillGap(t.skillId(), w.skills.get(t.skillId()).name(), t.target(), current, t.target() - current));
            }
        }
        gaps.sort(Comparator.comparingInt(SkillGap::gap).reversed().thenComparing(SkillGap::skillId));
        return gaps;
    }

    @GetMapping("/employees/{employeeId}/recommendations")
    public List<GapRecommendation> recommendations(@PathVariable String employeeId,
            @RequestParam(required = false) String date, @RequestParam(defaultValue = "3") int perSkill) {
        World w = datasets.world();
        employee(w, employeeId);
        LocalDate planning = planning(date);
        List<GapRecommendation> out = new ArrayList<>();
        for (SkillGap g : skillGaps(employeeId, date)) {
            List<CourseDecision> feasible = new ArrayList<>();
            Map<String, Long> rejected = new TreeMap<>();
            for (String courseId : w.coursesBySkill.getOrDefault(g.skillId(), List.of())) {
                Decision d = RecommendationRules.evaluate(w, employeeId, g.skillId(), courseId, planning);
                if (d == Decision.ACCEPT) feasible.add(decision(w, employeeId, courseId, d, planning));
                else rejected.merge(d.name(), 1L, Long::sum);
            }
            feasible.sort(Comparator.comparingInt(CourseDecision::cost).thenComparing(CourseDecision::courseId));
            int rejectedTotal = (int) rejected.values().stream().mapToLong(Long::longValue).sum();
            out.add(new GapRecommendation(g.skillId(), g.skillName(), g.target(), g.current(), g.gap(),
                    feasible.subList(0, Math.min(Math.max(perSkill, 0), feasible.size())), rejectedTotal, rejected));
        }
        return out;
    }

    @GetMapping("/employees/{employeeId}/skills/{skillId}/courses/{courseId}/decision")
    public CourseDecision decide(@PathVariable String employeeId, @PathVariable String skillId,
            @PathVariable String courseId, @RequestParam(required = false) String date) {
        World w = datasets.world();
        employee(w, employeeId);
        if (!w.courses.containsKey(courseId) || !w.skills.containsKey(skillId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown skill or course");
        }
        LocalDate planning = planning(date);
        return decision(w, employeeId, courseId, RecommendationRules.evaluate(w, employeeId, skillId, courseId, planning), planning);
    }

    /** One LLM call for one decision, on request only. The decision is fixed before the model is asked. */
    @GetMapping("/employees/{employeeId}/skills/{skillId}/courses/{courseId}/explanation")
    public Map<String, String> explain(@PathVariable String employeeId, @PathVariable String skillId,
            @PathVariable String courseId, @RequestParam(required = false) String date) {
        CourseDecision cd = decide(employeeId, skillId, courseId, date);
        World w = datasets.world();
        String facts = "Skill: " + w.skills.get(skillId).name() + ". Course: " + cd.courseName() + " (cost " + cd.cost()
                + ", teaches up to level " + cd.levelTaught() + "). Decision: " + cd.decision() + ". Reason: " + cd.reason() + ".";
        String prompt = "You explain training recommendations to employees. The decision below was already made by "
                + "deterministic business rules. Do not change, question or override it and do not invent facts. "
                + "Write 2-3 plain sentences.\n\n" + facts;
        var generated = ollama.tryGenerate(prompt);
        return Map.of("decision", cd.decision(), "reason", cd.reason(),
                "explanation", generated.orElse(facts),
                "explanationSource", generated.isPresent() ? "ollama" : "deterministic-fallback (Ollama unavailable)");
    }

    private Employee employee(World w, String employeeId) {
        Employee e = w.employees.get(employeeId);
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found: " + employeeId);
        return e;
    }

    private LocalDate planning(String date) {
        try {
            return date == null || date.isBlank() ? datasets.asOf() : LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD");
        }
    }

    private CourseDecision decision(World w, String employeeId, String courseId, Decision d, LocalDate planning) {
        Course c = w.courses.get(courseId);
        String reason = switch (d) {
            case ACCEPT -> "All rules satisfied: available, prerequisites met, capacity free, cost within remaining budget "
                    + w.remainingBudget(employeeId);
            case REJECT_UNAVAILABLE -> "Course is " + c.status() + ", available " + c.from() + " to " + c.until()
                    + " (planning date " + planning + ")";
            case REJECT_ALREADY_ENROLLED -> "Employee already has an enrollment for this course";
            case REJECT_NO_GAP_CLOSURE -> "Course does not raise this skill above the current level, or the skill has no open gap";
            case REJECT_PREREQUISITE -> "Prerequisite not passed: "
                    + RecommendationRules.missingPrerequisite(w, employeeId, courseId, planning);
            case REJECT_CAPACITY -> "Course capacity " + c.capacity() + " is full for the planned dates";
            case REJECT_BUDGET -> "Cost " + c.cost() + " exceeds remaining budget " + w.remainingBudget(employeeId);
        };
        return new CourseDecision(courseId, c.name(), c.cost(), c.level(), d.name(), reason);
    }
}
