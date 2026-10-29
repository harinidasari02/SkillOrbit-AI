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
