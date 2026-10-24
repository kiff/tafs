import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { putDefinition } from "../src/cloud/handler.js";
import { SETUP_INSTRUCTIONS, setupDefinition } from "../src/cloud/setupdef.js";
import { SKILLS } from "../src/coach-skills.js";
import { fakeAgents } from "./fakes.js";

const DIR = join(import.meta.dirname, "..", "skills");

/** skills/<name>/<version>/SKILL.md, as the files have them: the source of src/coach-skills.ts. */
function onDisk(): { name: string; version: string; skill_md: string }[] {
  const out = [];
  for (const name of readdirSync(DIR).sort()) {
    for (const version of readdirSync(join(DIR, name)).sort()) {
      const md = join(DIR, name, version, "SKILL.md");
      if (statSync(join(DIR, name, version)).isDirectory()) out.push({ name, version, skill_md: readFileSync(md, "utf8") });
    }
  }
  return out;
}

describe("Coach's skills (kiff/tafs#218)", () => {
  it("are the files under skills/, as scripts/coach-skills.sh writes them", () => {
    expect(SKILLS).toEqual(onDisk());
  });

  it("are each a SKILL.md kiff/agents takes: its name, a one-line description, a body", () => {
    for (const s of SKILLS) {
      const m = s.skill_md.match(/^---\nname: (.+)\ndescription: (.+)\n---\n\n# .+\n/);
      expect(m, s.name).not.toBeNull();
      expect(m![1]).toBe(s.name);
      expect(m![2].length).toBeLessThanOrEqual(1024);
      expect(s.name).toMatch(/^[a-z][a-z0-9-]{0,63}$/);
    }
    // kiff/agents takes at most 8 per definition.
    expect(SKILLS.length).toBeLessThanOrEqual(8);
  });

  it("are each named in Setup's instructions, which say when to read them", () => {
    const flat = SETUP_INSTRUCTIONS.replace(/\s+/g, " ");
    for (const s of SKILLS) expect(flat, s.name).toContain(s.name);
    expect(flat).toContain('A conversation that starts with "[improve taf <id>]" changes a taf the account already has (kiff/tafs#107): read the change-a-taf skill before your first answer');
    expect(flat).toContain("Read the one a conversation is about before you answer, even when the owner's words don't name it");
    expect(flat).toContain("Setting up a new taf needs none of them");
  });

  it("are stored for the account before the definition that names them, and storing again is fine", async () => {
    const agents = fakeAgents();
    await putDefinition({ agents: agents.api }, "t_acme", "setup-1", setupDefinition("setup-1"));
    await putDefinition({ agents: agents.api }, "t_acme", "setup-1", setupDefinition("setup-1"));
    const once = [...SKILLS.map((k) => `putSkill ${k.name}@${k.version}`), "putAgent setup-1"];
    expect(agents.calls).toEqual([...once, ...once]);
  });

  it("refuses a definition naming a skill tafs doesn't have, before writing anything", async () => {
    const agents = fakeAgents();
    const def = { ...setupDefinition("setup-1"), skills: [{ name: "nothing", version: "1" }] };
    await expect(putDefinition({ agents: agents.api }, "t_acme", "setup-1", def)).rejects.toThrow("names skill nothing@1, which tafs doesn't have");
    expect(agents.calls).toEqual([]);
  });
});
