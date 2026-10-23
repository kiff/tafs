import { AgentsError, type AIBound, type Notify, type AgentDefinition, type AgentVersion, type Note, type Run, type Schedule, type Session, type SessionEvent, type Switching, type TrialWrite } from "../src/cloud/agents.js";
import type { CoachStore } from "../src/cloud/coach.js";
import type { Accounts, AgentsAPI, Archived, Claim, Claims, Keys, Names, Removal, Removals, Slots, Taf, Tafs } from "../src/cloud/handler.js";

/**
 * kiff/agents in memory, per tenant, as its server behaves where tafs cloud
 * relies on it: a run starts in the session named or a new one (a chat for
 * the owner's runs, a run session for a schedule's), schedules start runs of
 * their agent, removing an agent removes everything kept for it, and
 * anything outside the tenant is 404. `fail` makes the next call of a
 * method throw.
 */
export function fakeAgents(now: () => Date = () => new Date("2026-10-16T12:00:00Z")) {
  const calls: string[] = [];
  /** AI fair use (kiff/tafs#193): each period's spend by `tenant period`, and the bound each chat came with. */
  const spent = new Map<string, number>();
  const aiSent: Array<AIBound | undefined> = [];
  /** Where each run asked to be announced (kiff/tafs#206), by run. */
  const notified = new Map<string, Notify>();
  const fairUse = (tenant: string, ai: AIBound | undefined) => {
    aiSent.push(ai);
    if (ai && ai.bound_micro_usd > 0 && (spent.get(`${tenant} ${ai.period}`) ?? 0) >= ai.bound_micro_usd) {
      throw new AgentsError(409, "ai_fair_use: this period's AI use is past its bound; the owner says go on in KIFF to continue");
    }
  };
  const agents = new Map<string, AgentDefinition>();
  const schedules = new Map<string, Schedule>();
  const runs: Array<Run & { tenant: string }> = [];
  const sessions: Array<Session & { tenant: string }> = [];
  const events = new Map<string, SessionEvent[]>();
  const notes = new Map<string, Note[]>();
  /** Drafts Setup submitted (kiff/agents#94): draft id → its agent, and where its key was claimed. */
  const setupDrafts = new Map<string, { tenant: string; agent: string; keyRef?: string }>();
  const fail = new Map<string, AgentsError | Error>();
  /** What a streamed run's agent does, in order: kiff/agents' stream events, or an Error that breaks it. */
  const streamed: Array<{ event: string; data: unknown } | Error> = [];
  let n = 0;

  const k = (t: string, id: string) => `${t}|${id}`;
  const gone = () => new AgentsError(404, "not found");
  const failing = (method: string) => {
    const err = fail.get(method);
    if (err) { fail.delete(method); throw err; }
  };
  const openSession = (tenant: string, agent: string, kind: Session["kind"]) => {
    const at = now().toISOString();
    const s = { tenant, id: `sess${++n}`, agent, kind, started: at, updated: at };
    sessions.unshift(s);
    return s;
  };
  const newRun = (tenant: string, agent: string, session: string, trigger: string, input: string, source?: string): Run & { tenant: string } => {
    const at = now().toISOString();
    const r = { tenant, id: `run${++n}`, agent, session, trigger, ...(source ? { source } : {}), status: "PENDING" as const, cost_micro_usd: 0, turns: 0, created: at, updated: at };
    runs.unshift(r);
    const list = events.get(session) ?? [];
    list.push({ session, run: r.id, seq: list.length + 1, time: at, kind: "owner_message", body: { text: input } });
    events.set(session, list);
    const s = sessions.find((x) => x.id === session);
    if (s) s.updated = at;
    return r;
  };

  /** Each agent's versions (kiff/tafs#107), and each trial's writes, by run. */
  const versions = new Map<string, AgentVersion[]>();
  const trialWrites = new Map<string, TrialWrite[]>();
  /** A switch under way, by agent, as kiff/agents keeps it (kiff/agents#137). */
  const switching = new Map<string, Switching>();
  /** Each version's definition, by agent and number. */
  const versionDefs = new Map<string, AgentDefinition>();
  /** Halts by agent, "" for the account itself. */
  const halts = new Map<string, { on: boolean; at: string }>();
  const halted = (tenant: string, agent?: string) => {
    const own = agent ? halts.get(k(tenant, agent)) : undefined;
    const all = halts.get(k(tenant, ""));
    const at = [own?.at, all?.at].filter(Boolean).sort().at(-1);
    return { ...(agent ? { agent } : {}), halted: Boolean(own?.on || all?.on), own: Boolean(own?.on), tenant: Boolean(all?.on), ...(at ? { halted_at: at } : {}) };
  };
  const api: AgentsAPI = {
    async versions(tenant, id) {
      calls.push(`versions ${id}`);
      if (!agents.has(k(tenant, id))) throw gone();
      const sw = switching.get(k(tenant, id));
      return { versions: (versions.get(k(tenant, id)) ?? []).map((v) => ({ ...v })), ...(sw ? { switching: { ...sw } } : {}) };
    },
    async switchVersion(tenant, id, n, cards) {
      calls.push(`switchVersion ${id} ${n}`);
      const list = versions.get(k(tenant, id)) ?? [];
      if (!list.some((v) => v.n === n)) throw gone();
      const sw = switching.get(k(tenant, id));
      if (sw && sw.n !== n) throw new AgentsError(409, "this agent is switching versions");
      for (const v of list) v.live = v.n === n;
      if (cards?.length) list.find((v) => v.n === n)!.cards = cards.map((c) => ({ ...c }));
      const def = versionDefs.get(`${k(tenant, id)}|${n}`);
      if (def) agents.set(k(tenant, id), { ...def });
      switching.delete(k(tenant, id));
    },
    async createVersion(tenant, id, v) {
      calls.push(`createVersion ${id}`);
      const live = agents.get(k(tenant, id));
      if (!live) throw gone();
      const list = versions.get(k(tenant, id)) ?? [];
      if (list.length === 0) { // stored before versions: what's live is version 1
        list.push({ n: 1, note: "As it was set up", by: "", created: now().toISOString(), digest: "d1", live: true });
        versionDefs.set(`${k(tenant, id)}|1`, { ...live });
      }
      const n = list.length + 1;
      list.push({ n, note: v.note, by: v.by, created: now().toISOString(), digest: `d${n}`, live: false, ...(v.draft ? { draft: v.draft } : {}) });
      versions.set(k(tenant, id), list);
      versionDefs.set(`${k(tenant, id)}|${n}`, { ...v.definition, tenant, id });
      return n;
    },
    async getVersion(tenant, id, n) {
      const version = (versions.get(k(tenant, id)) ?? []).find((v) => v.n === n);
      const definition = versionDefs.get(`${k(tenant, id)}|${n}`);
      if (!version || !definition) throw gone();
      return { version: { ...version }, definition: { ...definition } };
    },
    async beginSwitch(tenant, id, n, draft) {
      calls.push(`beginSwitch ${id} ${n}`);
      if (!(versions.get(k(tenant, id)) ?? []).some((v) => v.n === n)) throw gone();
      const was = switching.get(k(tenant, id));
      if (was) {
        if (was.n !== n) throw new AgentsError(409, "this agent is switching versions");
        return { ...was };
      }
      const blocking = runs.filter((r) => r.tenant === tenant && r.agent === id && r.trigger !== "trial" && !["COMPLETED", "ERROR", "CANCELLED"].includes(r.status));
      if (blocking.length) {
        throw new AgentsError(409, "a run of this agent is going or waiting on its owner: answer it, stop it or let it finish, then switch",
          blocking.map((r) => ({ id: r.id, session: r.session, status: r.status, trigger: r.trigger })));
      }
      const sw: Switching = { n, ...(draft ? { draft } : {}), at: now().toISOString() };
      switching.set(k(tenant, id), sw);
      return { ...sw };
    },
    async replaceSwitchDraft(tenant, id, n, draft) {
      calls.push(`replaceSwitchDraft ${id} ${n}`);
      const was = switching.get(k(tenant, id));
      if (!was || was.n !== n) throw new AgentsError(409, "this agent isn't switching to that version: begin the switch first");
      const sw: Switching = { ...was, draft };
      switching.set(k(tenant, id), sw);
      return { ...sw };
    },
    async cancelSwitch(tenant, id) {
      calls.push(`cancelSwitch ${id}`);
      return switching.delete(k(tenant, id));
    },
    async startTrial(tenant, id, n, input, notify) {
      calls.push(`startTrial ${id} ${n}`);
      if (!(versions.get(k(tenant, id)) ?? []).some((v) => v.n === n)) throw gone();
      const s = openSession(tenant, id, "trial" as Session["kind"]).id;
      const run = newRun(tenant, id, s, "trial", input);
      trialWrites.set(run.id, []);
      if (notify) notified.set(run.id, notify);
      return run;
    },
    async runs(tenant, agent, active = false, limit = 20) {
      failing("runs");
      if (!agents.has(k(tenant, agent))) throw gone();
      const ended = (st: string) => st === "COMPLETED" || st === "ERROR" || st === "CANCELLED";
      return runs.filter((r) => r.tenant === tenant && r.agent === agent && !(active && ended(r.status))).slice(0, limit).map((r) => ({ ...r }));
    },
    async trial(tenant, id, run) {
      const r = runs.find((x) => x.tenant === tenant && x.agent === id && x.id === run && x.trigger === "trial");
      if (!r) throw gone();
      return { run: { ...r }, writes: trialWrites.get(run) ?? [] };
    },
    async putAgent(tenant, id, def) {
      calls.push(`putAgent ${id}`);
      failing("putAgent");
      agents.set(k(tenant, id), { ...def, tenant, id });
      return { ...def, tenant, id };
    },
    async getAgent(tenant, id) {
      failing("getAgent");
      const d = agents.get(k(tenant, id));
      return d ? { ...d } : undefined;
    },
    async aiSpent(tenant, period) {
      calls.push(`aiSpent ${period}`);
      failing("aiSpent");
      return spent.get(`${tenant} ${period}`) ?? 0;
    },
    async eraseTenant(tenant) {
      calls.push("eraseTenant");
      failing("eraseTenant");
      for (const id of [...agents.keys()].filter((key) => key.startsWith(`${tenant}|`))) {
        agents.delete(id);
        schedules.delete(id);
        notes.delete(id);
      }
      for (const list of [runs, sessions] as Array<Array<{ tenant: string }>>) {
        for (let i = list.length - 1; i >= 0; i--) if (list[i]!.tenant === tenant) list.splice(i, 1);
      }
    },
    async deleteAgent(tenant, id) {
      calls.push(`deleteAgent ${id}`);
      failing("deleteAgent");
      agents.delete(k(tenant, id));
      schedules.delete(k(tenant, id));
      for (const list of [runs, sessions] as Array<Array<{ tenant: string; agent: string }>>) {
        for (let i = list.length - 1; i >= 0; i--) if (list[i]!.tenant === tenant && list[i]!.agent === id) list.splice(i, 1);
      }
      notes.delete(k(tenant, id));
    },
    async startRun(tenant, agent, input, session, ai, notify) {
      calls.push(`startRun ${agent} ${session ?? "new"}`);
      failing("startRun");
      if (!agents.has(k(tenant, agent))) throw gone();
      fairUse(tenant, ai);
      let s = session;
      if (s) {
        const found = sessions.find((x) => x.tenant === tenant && x.id === s);
        if (!found || found.agent !== agent) throw gone();
      } else {
        s = openSession(tenant, agent, "chat").id;
      }
      const run = newRun(tenant, agent, s, "owner", input);
      if (notify) notified.set(run.id, notify);
      return run;
    },
    /**
     * A foreground run's stream: the run, then `streamed` (what the test says the
     * agent does), then its end. `failing("streamRun")` refuses before it begins.
     */
    async *streamRun(tenant, agent, input, session, ai) {
      calls.push(`streamRun ${agent} ${session ?? "new"}`);
      failing("streamRun");
      if (!agents.has(k(tenant, agent))) throw gone();
      fairUse(tenant, ai);
      let s = session;
      if (s) {
        const found = sessions.find((x) => x.tenant === tenant && x.id === s);
        if (!found || found.agent !== agent) throw gone();
      } else {
        s = openSession(tenant, agent, "chat").id;
      }
      const r = newRun(tenant, agent, s, "owner", input);
      yield { event: "run", data: r };
      for (const e of streamed) {
        if (e instanceof Error) throw e;
        yield e;
      }
      (r as { status: string }).status = "COMPLETED";
      yield { event: "end", data: { ...r, stop: "answered" } };
    },
    async sessions(tenant, agent, limit = 20) {
      failing("sessions");
      return sessions.filter((s) => s.tenant === tenant && s.agent === agent)
        .sort((a, b) => b.updated.localeCompare(a.updated)).slice(0, limit);
    },
    async session(tenant, id) {
      const s = sessions.find((x) => x.tenant === tenant && x.id === id);
      if (!s) throw gone();
      return { session: s, events: events.get(id) ?? [] };
    },
    async memories(tenant, agent) {
      if (!agents.has(k(tenant, agent))) throw gone();
      return notes.get(k(tenant, agent)) ?? [];
    },
    async forget(tenant, agent, name) {
      calls.push(`forget ${agent} ${name}`);
      if (!agents.has(k(tenant, agent))) throw gone();
      notes.set(k(tenant, agent), (notes.get(k(tenant, agent)) ?? []).filter((x) => x.name !== name));
    },
    async createSchedule(tenant, sc) {
      calls.push(`createSchedule ${sc.id} ${sc.cron} ${sc.time_zone ?? ""}`);
      failing("createSchedule");
      if (schedules.has(k(tenant, sc.id))) throw new AgentsError(409, "the schedule exists");
      const s = { ...sc, tenant, ...(sc.enabled ? { next: "2026-10-16T12:30:00Z" } : {}) };
      schedules.set(k(tenant, sc.id), s);
      return s;
    },
    async getSchedule(tenant, id) {
      return schedules.get(k(tenant, id));
    },
    // Halts (kiff/kiff-cloud#1061), as kiff/agents keeps them: by agent, "" for the account.
    async halt(tenant, agent) {
      calls.push(`halt ${agent ?? "all"}`);
      failing("halt");
      if (agent && !agents.has(k(tenant, agent))) throw gone();
      const was = halts.get(k(tenant, agent ?? ""));
      if (!was?.on) halts.set(k(tenant, agent ?? ""), { on: true, at: now().toISOString() });
      const active = runs.filter((r) => r.tenant === tenant && (!agent || r.agent === agent) && ["PENDING", "RUNNING", "PAUSED"].includes(r.status));
      for (const r of active) if (r.status === "PENDING") r.status = "CANCELLED";
      return { ...halted(tenant, agent), stopped: active.length };
    },
    async resume(tenant, agent) {
      calls.push(`resume ${agent ?? "all"}`);
      if (agent && !agents.has(k(tenant, agent))) throw gone();
      const h = halts.get(k(tenant, agent ?? ""));
      if (h) h.on = false;
      return halted(tenant, agent);
    },
    async halted(tenant, agent) {
      if (agent && !agents.has(k(tenant, agent))) throw gone();
      return halted(tenant, agent);
    },
    async switchSchedule(tenant, id, on) {
      calls.push(`switchSchedule ${id} ${on ? "on" : "off"}`);
      const s = schedules.get(k(tenant, id));
      if (!s) throw gone();
      const { next: _next, ...rest } = s;
      const out = { ...rest, enabled: on, ...(on ? { next: "2026-10-16T12:30:00Z" } : {}) };
      schedules.set(k(tenant, id), out);
      return out;
    },
    async trigger(tenant, id) {
      calls.push(`trigger ${id}`);
      failing("trigger");
      const s = schedules.get(k(tenant, id));
      if (!s) throw gone();
      return newRun(tenant, s.agent, openSession(tenant, s.agent, "run").id, "schedule", s.input, id);
    },
    async claimTafDraft(tenant, draftID, keyRef) {
      calls.push(`claimTafDraft ${draftID} ${keyRef}`);
      failing("claimTafDraft");
      const d = setupDrafts.get(draftID);
      if (!d || d.tenant !== tenant) throw gone();
      if (d.keyRef && d.keyRef !== keyRef) throw new AgentsError(409, "this draft's key was already claimed into another key_ref");
      d.keyRef = keyRef;
      return { agent_id: d.agent };
    },
    async getRun(tenant, agent, id) {
      failing("getRun");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      if (!r) throw gone();
      return r;
    },
    async steerRun(tenant, agent, id, text) {
      calls.push(`steerRun ${agent} ${id}`);
      failing("steerRun");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      if (!r) throw gone();
      if (r.status !== "PENDING" && r.status !== "RUNNING" && r.status !== "PAUSED") throw new AgentsError(409, "the run has ended, or was asked to stop");
      r.steering = [...(r.steering ?? []), { text, at: now().toISOString() }];
      return r;
    },
    async continueHeld(tenant, agent, exception) {
      calls.push(`continueHeld ${agent} ${exception}`);
      failing("continueHeld");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && (x.requirements ?? []).some((q) => q.exception === exception));
      if (!r) throw gone();
      if (r.status !== "PAUSED") throw new AgentsError(409, "the run isn't paused: read it");
      return r;
    },
    async cancelRun(tenant, agent, id) {
      calls.push(`cancelRun ${agent} ${id}`);
      failing("cancelRun");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      if (!r) throw gone();
      if (r.status !== "PENDING" && r.status !== "RUNNING" && r.status !== "PAUSED") throw new AgentsError(409, "the run is over");
      r.status = "CANCELLED";
      return r;
    },
    async scheduleRuns(tenant, id, limit = 20) {
      if (!schedules.has(k(tenant, id))) throw gone();
      return runs.filter((r) => r.tenant === tenant && r.source === id).slice(0, limit);
    },
  };

  /** What the agent said in a run, as kiff/agents records it: a job run's last answer is its report. */
  const say = (run: Run, text: string, at?: string) => {
    const s = sessions.find((x) => x.id === run.session)!;
    const list = events.get(run.session) ?? [];
    const time = at ?? now().toISOString();
    list.push({ session: run.session, run: run.id, seq: list.length + 1, time, kind: s.kind === "run" ? "report" : "agent_message", body: { text } });
    events.set(run.session, list);
    s.updated = time;
  };
  /** A component the agent showed in a run (kiff/agents#91), as kiff/agents records it. */
  const show = (run: Run, component: object, at?: string) => {
    const s = sessions.find((x) => x.id === run.session)!;
    const list = events.get(run.session) ?? [];
    const time = at ?? now().toISOString();
    list.push({ session: run.session, run: run.id, seq: list.length + 1, time, kind: "component", body: component });
    events.set(run.session, list);
    s.updated = time;
  };
  return { api, calls, spent, aiSent, notified, agents, schedules, runs, sessions, events, notes, fail, say, show, setupDrafts, streamed, versions, trialWrites, switching, versionDefs, halts };
}

/** Names claimed atomically for an owner; released only by that owner, or by anyone for a claim without one. */
export function memoryNames(): Names & { held: Map<string, string> } {
  const held = new Map<string, string>();
  return {
    held,
    async claim(t, n, owner) {
      if (held.has(`${t}#${n}`)) return false;
      held.set(`${t}#${n}`, owner);
      return true;
    },
    async release(t, n, owner) {
      const o = held.get(`${t}#${n}`);
      if (o === undefined || o === owner) held.delete(`${t}#${n}`);
    },
  };
}

export function memoryClaims(): Claims & { held: Map<string, Claim> } {
  const held = new Map<string, Claim>();
  return {
    held,
    async get(t, d) { return held.get(`${t}#${d}`); },
    async put(t, d, c) { held.set(`${t}#${d}`, c); },
    async remove(t, d) { held.delete(`${t}#${d}`); },
  };
}

export function memoryRemovals(): Removals & { held: Map<string, Removal> } {
  const held = new Map<string, Removal>();
  return {
    held,
    async list(t) { return [...held.entries()].filter(([k]) => k.startsWith(`${t}#`)).map(([, r]) => r); },
    async add(t, r) { held.set(`${t}#${r.id}`, r); },
    async done(t, id) { held.delete(`${t}#${id}`); },
  };
}

export function memoryTafs(): Tafs & { held: Map<string, Taf> } {
  const held = new Map<string, Taf>();
  return {
    held,
    async list(t) { return [...held.entries()].filter(([k]) => k.startsWith(`${t}#`)).map(([, x]) => x); },
    async get(t, id) { return held.get(`${t}#${id}`); },
    async put(t, taf) { held.set(`${t}#${taf.id}`, { ...taf }); },
    async putIf(t, taf, read) {
      const now = held.get(`${t}#${taf.id}`);
      if (!now || JSON.stringify(now) !== JSON.stringify(read)) return false;
      held.set(`${t}#${taf.id}`, { ...taf });
      return true;
    },
    async remove(t, id) { held.delete(`${t}#${id}`); },
    async tenants() { return [...new Set([...held.keys()].map((k) => k.slice(0, k.indexOf("#"))))]; },
  };
}

/** Kept keys, by tenant and taf. `fail` makes the next put or remove throw. */
export function memoryKeys(): Keys & { held: Map<string, string>; fail: Set<"put" | "remove"> } {
  const held = new Map<string, string>();
  const fail = new Set<"put" | "remove">();
  return {
    held,
    fail,
    async put(t, id, key) {
      if (fail.delete("put")) throw new Error("Secrets Manager is down");
      held.set(`${t}#${id}`, key);
    },
    async remove(t, id) {
      if (fail.delete("remove")) throw new Error("Secrets Manager is down");
      held.delete(`${t}#${id}`);
    },
  };
}

export function memoryAccounts(): Accounts & { held: Map<string, { tenant: string; secret: string }> } {
  const held = new Map<string, { tenant: string; secret: string }>();
  return { held, async put(account, tenant, secret) { held.set(account, { tenant, secret }); } };
}

/** Slots claimed atomically, like the DynamoDB conditional write. */
export function memorySlots(): Slots & { taken: Set<string> } {
  const taken = new Set<string>();
  return {
    taken,
    async claim(key) { if (taken.has(key)) return false; taken.add(key); return true; },
    async release(key) { taken.delete(key); },
    async keep() {},
    async held(key) { return taken.has(key); },
  };
}

/** Archived chats, by tenant and taf (kiff/tafs#82). */
export function memoryArchived(): Archived & { held: Map<string, Set<string>> } {
  const held = new Map<string, Set<string>>();
  return {
    held,
    async list(t, taf) { return [...(held.get(`${t}#${taf}`) ?? [])]; },
    async add(t, taf, session) { held.set(`${t}#${taf}`, new Set([...(held.get(`${t}#${taf}`) ?? []), session])); },
    async unarchive(t, taf, session) { held.get(`${t}#${taf}`)?.delete(session); },
    async remove(t, taf) { held.delete(`${t}#${taf}`); },
  };
}

/** Coach's store in memory (kiff/tafs#206), ttls by the given clock. */
export function memoryCoach(now: () => Date = () => new Date("2026-10-16T12:00:00Z")): CoachStore & { held: Map<string, { value: unknown; until: number }> } {
  const held = new Map<string, { value: unknown; until: number }>();
  const live = (key: string) => {
    const it = held.get(key);
    return it && it.until > now().getTime() ? it : undefined;
  };
  return {
    held,
    async once(key, expiresAt) {
      if (live(key)) return false;
      held.set(key, { value: true, until: expiresAt.getTime() });
      return true;
    },
    async get(key) { return structuredClone(live(key)?.value); },
    async put(key, value, expiresAt) { held.set(key, { value: structuredClone(value), until: expiresAt.getTime() }); },
    async append(key, item, expiresAt) {
      const list = (live(key)?.value as unknown[] | undefined) ?? [];
      held.set(key, { value: [...list, structuredClone(item)], until: expiresAt.getTime() });
    },
    async take(key) {
      const list = (live(key)?.value as unknown[] | undefined) ?? [];
      held.delete(key);
      return list;
    },
    async remove(key) { held.delete(key); },
  };
}
