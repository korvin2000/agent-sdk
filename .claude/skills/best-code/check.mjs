#!/usr/bin/env node
// Structural check for the best-code skill.
// Enforces the token budgets and keeps the router and the reference files in sync.
// Run: node .claude/skills/best-code/check.mjs

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, relative } from "node:path";

const root = dirname(fileURLToPath(import.meta.url));
const LIMITS = { skill: 100, reference: 160 };

const problems = [];
const note = (m) => problems.push(m);

// --- SKILL.md ------------------------------------------------------------
const skillPath = join(root, "SKILL.md");
if (!existsSync(skillPath)) {
  console.error("FAIL  SKILL.md is missing");
  process.exit(1);
}
const skill = readFileSync(skillPath, "utf8");
const skillLines = skill.split(/\r?\n/).length;

const fm = skill.match(/^---\r?\n([\s\S]*?)\r?\n---/);
if (!fm) note("SKILL.md has no YAML frontmatter");
else {
  for (const key of ["name", "description"]) {
    if (!new RegExp(`^${key}:\\s*\\S`, "m").test(fm[1])) {
      note(`SKILL.md frontmatter is missing "${key}"`);
    }
  }
  const name = fm[1].match(/^name:\s*(\S+)/m)?.[1];
  if (name && name !== "best-code") note(`frontmatter name is "${name}", expected "best-code"`);
  const desc = fm[1].match(/^description:\s*(.+)$/m)?.[1] ?? "";
  // The description is what the harness scans to decide whether to load the skill.
  const verbs = ["design", "implement", "refactor", "review"];
  const missing = verbs.filter((v) => !desc.toLowerCase().includes(v));
  if (missing.length) note(`description lacks trigger verb(s): ${missing.join(", ")}`);
}

if (skillLines > LIMITS.skill) {
  note(`SKILL.md is ${skillLines} lines, budget is ${LIMITS.skill}`);
}

// --- router vs. filesystem ----------------------------------------------
const routed = new Set([...skill.matchAll(/`(reference\/[a-z0-9-]+\.md)`/g)].map((m) => m[1]));

const refDir = join(root, "reference");
const present = existsSync(refDir)
  ? new Set(readdirSync(refDir).filter((f) => f.endsWith(".md")).map((f) => `reference/${f}`))
  : new Set();

for (const r of routed) {
  if (!present.has(r)) note(`SKILL.md routes to ${r}, which does not exist`);
}
for (const p of present) {
  if (!routed.has(p)) note(`${p} exists but no route in SKILL.md points to it`);
}

// --- reference budgets and internal links -------------------------------
const sizes = [];
for (const p of [...present].sort()) {
  const abs = join(root, p);
  const body = readFileSync(abs, "utf8");
  const lines = body.split(/\r?\n/).length;
  sizes.push([p, lines]);
  if (lines > LIMITS.reference) {
    note(`${p} is ${lines} lines, budget is ${LIMITS.reference}`);
  }
  for (const m of body.matchAll(/`(reference\/[a-z0-9-]+\.md)`/g)) {
    if (!present.has(m[1])) note(`${p} links to ${m[1]}, which does not exist`);
  }
}

// --- report --------------------------------------------------------------
const total = skillLines + sizes.reduce((a, [, n]) => a + n, 0);
const biggestTwo = sizes.map(([, n]) => n).sort((a, b) => b - a).slice(0, 2);
const row = (label, n, limit) =>
  console.log(`${label.padEnd(34)}${String(n).padStart(4)}${limit ? ` / ${limit}` : ""}`);

row("SKILL.md", skillLines, LIMITS.skill);
for (const [p, n] of sizes) row(p, n, LIMITS.reference);
console.log("—".repeat(42));
row("loaded per task (entry point)", skillLines);
row("worst case (entry + 2 largest refs)", skillLines + biggestTwo.reduce((a, b) => a + b, 0));
row("whole tree (never loaded at once)", total);

if (problems.length) {
  console.error(`\n${problems.length} problem(s):`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`\nOK  ${relative(process.cwd(), root) || "."} is consistent`);
