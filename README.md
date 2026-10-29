# SkillOrbit AI

A Java 17 / Spring Boot 3.5 application and deterministic synthetic-data generator for employee skill-gap analysis and course recommendations.

## Features
- Deterministic synthetic datasets with fixed seed `20261022`
- Employee skills, course recommendations, prerequisites, capacity, availability, and budget rules
- Enrollment, completion, and reassessment tracking
- CSV, JSON, and SQL exports
- Data validation, edge-case ground truth, and reproducibility evidence
- Optional Ollama-based explanations

## Project structure
The complete application is in the [`skillorbit-ai`](./skillorbit-ai/) directory.

Inside it:
- `src/` — Java source code and tests
- `synthetic-output/` — starter and full-scale datasets
- `docs/` — documentation and validation evidence
- `scripts/` — dataset generation and validation scripts
- `pom.xml` — Maven configuration

## Getting started
Open the [`skillorbit-ai` directory](./skillorbit-ai/) and follow its README for setup, dataset generation, validation, and test commands.

## AI use
See [`docs/AI_PROMPT_HISTORY.md`](./skillorbit-ai/docs/AI_PROMPT_HISTORY.md) for the AI-use disclosure and prompt history.
