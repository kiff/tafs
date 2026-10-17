import { AgentsError, type AgentDefinition, type Note, type Run, type Schedule, type Session, type SessionEvent } from "../src/cloud/agents.js";
import type { Accounts, AgentsAPI, Claim, Claims, Keys, Names, Removal, Removals, Slots, Taf, Tafs } from "../src/cloud/handler.js";

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
  const agents = new Map<string, AgentDefinition>();
  const schedules = new Map<string, Schedule>();
  const runs: Array<Run & { tenant: string }> = [];
  const sessions: Array<Session & { tenant: string }> = [];
  const events = new Map<string, SessionEvent[]>();
  const notes = new Map<string, Note[]>();
  const fail = new Map<string, AgentsError | Error>();
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

  const api: AgentsAPI = {
    async putAgent(tenant, id, def) {
      calls.push(`putAgent ${id}`);
      failing("putAgent");
      agents.set(k(tenant, id), { ...def, tenant, id });
      return { ...def, tenant, id };
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
    async startRun(tenant, agent, input, session) {
      calls.push(`startRun ${agent} ${session ?? "new"}`);
      failing("startRun");
      if (!agents.has(k(tenant, agent))) throw gone();
      let s = session;
      if (s) {
        const found = sessions.find((x) => x.tenant === tenant && x.id === s);
        if (!found || found.agent !== agent) throw gone();
      } else {
        s = openSession(tenant, agent, "chat").id;
      }
      return newRun(tenant, agent, s, "owner", input);
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
  return { api, calls, agents, schedules, runs, sessions, events, notes, fail, say, show };
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
    async remove(t, id) { held.delete(`${t}#${id}`); },
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
