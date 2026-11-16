import { describe, expect, it } from "vitest";
import type { LabelSet } from "../src/cloud/labels.js";
import { type MissionSet, planMission } from "../src/cloud/missions.js";

// A mission's label on its tafs (docs/missions.md), worked out from one read, writing nothing.
describe("planMission", () => {
  const ids = (() => { let n = 0; return (p: string) => `${p}_${++n}`; })();
  const at = (labels: LabelSet, missions: MissionSet, tafs: Array<{ id: string; name: string; labels?: string[]; mission?: string }>) =>
    ({ labels, missions, tafs: tafs.map((t) => ({ ...t, kiffAgent: `agent_${t.id}` })), now: new Date("2026-11-14T18:00:00Z"), by: "u_ana", newID: ids });

  it("takes an existing label of the same name, refuses a 4th label on a taf, and brings a dropped label back by name", () => {
    const vip: LabelSet = { labels: [{ id: "lbl_vip", name: "VIP", color: "errands" }], version: 1 };
    const plan = planMission("create", { name: "vip", goal: "Keep the big clients happy.", tafs: ["a"] }, at(vip, { missions: [], version: 0 }, [{ id: "a", name: "A", labels: ["lbl_vip"] }]));
    expect(plan.missions[0]!.label).toBe("lbl_vip");
    expect(plan.tafs).toEqual([{ id: "a", kiffAgent: "agent_a", labels: ["lbl_vip"], mission: plan.missions[0]!.id }]);
    const full: LabelSet = { labels: ["x", "y", "z"].map((id) => ({ id, name: id.toUpperCase(), color: "money" })), version: 1 };
    expect(() => planMission("create", { name: "Ops", goal: "Run operations.", tafs: ["a"] }, at(full, { missions: [], version: 0 }, [{ id: "a", name: "A", labels: ["x", "y", "z"] }])))
      .toThrow("A: a taf has at most 3 labels");
    // Its label was dropped when its last taf left: adding a taf makes it again, by the mission's name.
    const empty: MissionSet = { missions: [{ id: "msn_1", name: "Growth", goal: "Find new clients.", label: "lbl_gone", tafs: [], created: "", by: "u_ana" }], version: 1 };
    const added = planMission("add", { mission: "Growth", taf: "b" }, at({ labels: [], version: 2 }, empty, [{ id: "b", name: "B" }]));
    expect(added.labels.map((l) => l.name)).toEqual(["Growth"]);
    expect(added.missions[0]).toMatchObject({ label: added.labels[0]!.id, tafs: ["b"] });
  });
});
