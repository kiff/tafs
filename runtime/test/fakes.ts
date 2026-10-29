import { AgentsError, type AIBound, type AttachmentRef, type Notify, type AgentDefinition, type AgentVersion, type Note, type Run, type RunAt, type Schedule, type Session, type SessionEvent, type Switching, type Trial, type TrialSummary, type TrialWrite } from "../src/cloud/agents.js";
import type { CoachStore } from "../src/cloud/coach.js";
import type { Accounts, AgentsAPI, Archived, Claim, Claims, Keys, Names, Removal, Removals, Slots, Taf, Tafs } from "../src/cloud/handler.js";

/**
 * kiff/agents in memory, per tenant, as its server behaves where tafs cloud
 * relies on it: a run starts in the session named or a new one (a chat for
 * the owner's runs, a run session for a schedule's), a requester's new session
 * is theirs and their live chat is their latest one active in the last 30
 * minutes and not archived, each requester has 10 messages in 10 minutes,
 * schedules start runs of
 * their agent, removing an agent removes everything kept for it, and
 * anything outside the tenant is 404. `fail` makes the next call of a
 * method throw.
 */
export function fakeAgents(now: () => Date = () => new Date("2026-10-16T12:00:00Z")) {
  /** What KIFF says each person may do now, for Coach's key: `{cards_manage}` or `{}` (it didn't say). */
  const access = new Map<string, { cards_manage?: boolean }>();
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
  /** What a resumed run's stream sends before its end: its recorded events (kiff/tafs#285). */
  const resumed: Array<{ event: string; data: unknown }> = [];
  let n = 0;

  const k = (t: string, id: string) => `${t}|${id}`;
  const gone = () => new AgentsError(404, "not found");
  const failing = (method: string) => {
    const err = fail.get(method);
    if (err) { fail.delete(method); throw err; }
  };
  /** As kiff/agents decides it: a session is its owner's; one with no owner is the taf's only when it's a job's or a trial's, never otherwise. */
  const ownedBy = (s: Session, owner: string) => s.owner === owner || (!s.owner && (s.kind === "run" || s.kind === "trial"));
  /** Going on in a session, as kiff/agents decides it (ContinuedBy): its owner exactly; one with no owner, only a caller naming no one. */
  const continuedBy = (s: Session, owner: string) => (s.owner ?? "") === owner;
  const openSession = (tenant: string, agent: string, kind: Session["kind"], owner?: string) => {
    const at = now().toISOString();
    const s: Session & { tenant: string } = { tenant, id: `sess${++n}`, agent, kind, started: at, updated: at, ...(owner ? { owner } : {}) };
    sessions.unshift(s);
    return s;
  };
  /** Where a requester's run goes, as kiff/agents decides it: the session, its owner's, and whether it opened it. */
  const place = (tenant: string, agent: string, at: RunAt): { session: string; opened: boolean } => {
    const owner = at.requester ? `${at.requester.kind}:${at.requester.id}` : "";
    // A door's senders have their door's own daily caps in tafs cloud; a person and an
    // agent asking on its own have the flood guard.
    if (at.requester && at.requester.kind !== "door") {
      const since = now().getTime() - 10 * 60_000;
      const sent = runs.filter((r) => r.tenant === tenant && r.agent === agent && r.requester && `${r.requester.kind}:${r.requester.id}` === owner && Date.parse(r.created) > since);
      if (sent.length >= 10) throw new AgentsError(429, "that's 10 messages in 10 minutes; wait a little before writing again");
    }
    if (at.session) {
      const found = sessions.find((x) => x.tenant === tenant && x.id === at.session);
      if (!found || found.agent !== agent || !continuedBy(found, owner)) throw gone();
      return { session: found.id, opened: false };
    }
    if (at.live && owner) {
      const latest = sessions.filter((x) => x.tenant === tenant && x.agent === agent && x.owner === owner && x.kind === "chat")
        .sort((a, b) => b.updated.localeCompare(a.updated))[0];
      if (latest && !latest.archived && now().getTime() - Date.parse(latest.updated) < 30 * 60_000) return { session: latest.id, opened: false };
    }
    const opened = openSession(tenant, agent, "chat");
    if (owner) opened.owner = owner;
    return { session: opened.id, opened: true };
  };
  /** Files people attached (kiff/tafs#209), by id, with their owner and whether a message sent them. */
  const files = new Map<string, { tenant: string; agent: string; owner: string; sent: boolean; ref: AttachmentRef }>();
  /** The files a message names, as kiff/agents takes them: the requester's own, unsent, for this agent. */
  const take = (tenant: string, agent: string, at: RunAt): AttachmentRef[] => {
    const ids = at.attachments ?? [];
    if (ids.length && at.requester?.kind !== "member") throw new AgentsError(400, "only a person's own chat message carries attachments");
    return ids.map((id) => {
      const f = files.get(id);
      if (!f || f.tenant !== tenant || f.agent !== agent || f.owner !== `member:${at.requester?.id}`) throw gone();
      if (f.sent) throw new AgentsError(409, "a file was already sent with another message: attach it again");
      f.sent = true;
      return f.ref;
    });
  };
  const newRun = (tenant: string, agent: string, session: string, trigger: string, input: string, source?: string, attached: AttachmentRef[] = []): Run & { tenant: string } => {
    const at = now().toISOString();
    const r = { tenant, id: `run${++n}`, agent, session, trigger, ...(source ? { source } : {}), status: "PENDING" as const, cost_micro_usd: 0, turns: 0, created: at, updated: at };
    runs.unshift(r);
    const list = events.get(session) ?? [];
    list.push({ session, run: r.id, seq: list.length + 1, time: at, kind: "owner_message", body: { text: input, ...(attached.length ? { attachments: attached } : {}) } });
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
  /** Each agent's open trial (kiff/tafs#173), as kiff/agents keeps it. */
  const trials = new Map<string, Trial>();
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
    async openTrial(tenant, id, version, draft, requester) {
      calls.push(`openTrial ${id} ${version} ${draft}`);
      if (!(versions.get(k(tenant, id)) ?? []).some((v) => v.n === version)) throw gone();
      const open = trials.get(k(tenant, id));
      if (open && (open.n !== version || open.draft !== draft)) throw new AgentsError(409, `this agent is already trying version ${open.n}: end that trial first`);
      if (open) return { ...open };
      const at = now().toISOString();
      // kiff/agents chooses the trial's chat when it opens (kiff/agents#215).
      // A trial a person opens is theirs (kiff/agents' OpenOwned).
      const owner = requester ? `${requester.kind}:${requester.id}` : undefined;
      const t: Trial = { id: `tr${++n}`, n: version, draft, session: openSession(tenant, id, "trial", owner).id, started: at, used: at };
      trials.set(k(tenant, id), t);
      return { ...t };
    },
    async openedTrial(tenant, id) {
      const t = trials.get(k(tenant, id));
      return t && { ...t };
    },
    async trialMessage(tenant, id, input, requester) {
      calls.push(`trialMessage ${id}`);
      const t = trials.get(k(tenant, id));
      if (!t) throw new AgentsError(404, "this agent has no open trial");
      // Only its owner goes on in a trial's chat (kiff/agents' ContinuedBy).
      const s = sessions.find((x) => x.id === t.session);
      if (s?.owner && s.owner !== (requester ? `${requester.kind}:${requester.id}` : "")) throw gone();
      const run = newRun(tenant, id, t.session!, "trial", input);
      t.used = now().toISOString();
      return { run: { ...run }, trial: { ...t } };
    },
    async endTrial(tenant, id, why, counts) {
      failing("endTrial");
      calls.push(`endTrial ${id} ${why} ${counts.calls}/${counts.held}/${counts.refused}`);
      const t = trials.get(k(tenant, id));
      if (!t) throw new AgentsError(404, "this agent has no open trial");
      const ended: TrialSummary = { started: t.started, ended: now().toISOString(), why, ...counts };
      const v = (versions.get(k(tenant, id)) ?? []).find((x) => x.n === t.n);
      if (v) v.trials = [...(v.trials ?? []), ended];
      if (t.session) {
        const i = sessions.findIndex((x) => x.id === t.session);
        if (i >= 0) sessions.splice(i, 1);
        events.delete(t.session);
      }
      trials.delete(k(tenant, id));
      return ended;
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
    /** A file the person will attach: kiff/agents' limits, kept short here. */
    async createAttachment(tenant, agent, owner, file) {
      calls.push(`createAttachment ${agent} ${owner}`);
      failing("createAttachment");
      if (!agents.has(k(tenant, agent))) throw gone();
      if (file.size > 10 * 1024 * 1024) throw new AgentsError(400, `${file.name} is over the most a file may weigh`);
      const id = `att_${String(++n).padStart(32, "0")}`;
      const kind = file.media_type.startsWith("image/") ? "image" as const : file.media_type === "application/pdf" ? "pdf" as const : "text" as const;
      const ref = { id, kind, media_type: file.media_type, name: file.name, size: file.size };
      files.set(id, { tenant, agent, owner, sent: false, ref });
      return { attachment: { ...ref, created: now().toISOString(), sent: false }, upload: { url: `https://s3.test/${id}`, method: "PUT", headers: { "Content-Type": file.media_type }, expires: now().toISOString() } };
    },
    async readAttachment(tenant, agent, owner, id) {
      calls.push(`readAttachment ${agent} ${owner} ${id}`);
      const f = files.get(id);
      if (!f || f.tenant !== tenant || f.agent !== agent || f.owner !== owner) throw gone();
      return { ...f.ref, created: now().toISOString(), sent: f.sent, url: `https://s3.test/${id}?signed`, url_expires: now().toISOString() };
    },
    async startRun(tenant, agent, input, at = {}, ai, notify) {
      calls.push(`startRun ${agent} ${at.session ?? (at.live ? "live" : "new")}`);
      failing("startRun");
      if (!agents.has(k(tenant, agent))) throw gone();
      fairUse(tenant, ai);
      // A run grant names its person, as kiff/agents resolves it with KIFF: here "krg_<subject>".
      if (at.grant) at = { ...at, requester: { kind: "member", id: at.grant.replace(/^krg_/, "") } };
      const attached = take(tenant, agent, at);
      const { session, opened } = place(tenant, agent, at);
      const run = newRun(tenant, agent, session, "owner", input, undefined, attached);
      if (at.requester) run.requester = { ...at.requester };
      if (notify) notified.set(run.id, notify);
      // The run as stored, so a test that changes it changes kiff/agents' copy.
      return Object.assign(run, { new_session: opened });
    },
    /**
     * A foreground run's stream: the run, then `streamed` (what the test says the
     * agent does), then its end. `failing("streamRun")` refuses before it begins.
     */
    async *streamRun(tenant, agent, input, at = {}, ai, notify) {
      calls.push(`streamRun ${agent} ${at.session ?? (at.live ? "live" : "new")}`);
      failing("streamRun");
      if (!agents.has(k(tenant, agent))) throw gone();
      fairUse(tenant, ai);
      const attached = take(tenant, agent, at);
      const { session, opened } = place(tenant, agent, at);
      const r = newRun(tenant, agent, session, "owner", input, undefined, attached);
      if (at.requester) r.requester = { ...at.requester };
      if (notify) notified.set(r.id, notify);
      yield { event: "run", data: { ...r, new_session: opened } };
      for (const e of streamed) {
        if (e instanceof Error) throw e;
        yield e;
      }
      (r as { status: string }).status = "COMPLETED";
      yield { event: "end", data: { ...r, stop: "answered" } };
    },
    /** A run's stream picked back up: only the requester's own run; `resumed`, then its end. */
    async *resumeRun(tenant, agent, run, after, requester) {
      calls.push(`resumeRun ${agent} ${run} ${after}`);
      failing("resumeRun");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === run);
      if (!r || `${r.requester?.kind}:${r.requester?.id}` !== requester) throw gone();
      for (const e of resumed) yield e;
      yield { event: "end", data: { ...r } };
    },
    async sessions(tenant, agent, limit = 20, owner) {
      failing("sessions");
      return sessions.filter((s) => s.tenant === tenant && s.agent === agent && (!owner || ownedBy(s, owner)))
        .sort((a, b) => b.updated.localeCompare(a.updated)).slice(0, limit);
    },
    async session(tenant, id, owner) {
      const s = sessions.find((x) => x.tenant === tenant && x.id === id);
      if (!s || (owner && !ownedBy(s, owner))) throw gone();
      return { session: s, events: events.get(id) ?? [] };
    },
    async archive(tenant, id, archived, owner) {
      calls.push(`${archived ? "archive" : "unarchive"} ${id}`);
      failing("archive");
      const s = sessions.find((x) => x.tenant === tenant && x.id === id);
      if (!s || (owner && s.owner !== owner)) throw gone();
      s.archived = archived;
      return { ...s };
    },
    async memories(tenant, agent, owner) {
      if (!agents.has(k(tenant, agent))) throw gone();
      return (notes.get(k(tenant, agent)) ?? []).filter((x) => !owner || !x.scope || x.scope === owner);
    },
    async forget(tenant, agent, name, scope) {
      calls.push(`forget ${agent} ${scope ? `${scope} ` : ""}${name}`);
      if (!agents.has(k(tenant, agent))) throw gone();
      notes.set(k(tenant, agent), (notes.get(k(tenant, agent)) ?? []).filter((x) => !(x.name === name && (x.scope ?? "") === (scope ?? ""))));
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
    // Halts, as kiff/agents keeps them: by agent, "" for the account.
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
    async steerRun(tenant, agent, id, text, requester) {
      calls.push(`steerRun ${agent} ${id}`);
      failing("steerRun");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      // Another person's run is not found; tafs cloud itself (no requester) may write to any.
      if (!r || (requester && r.requester && (r.requester.kind !== requester.kind || r.requester.id !== requester.id))) throw gone();
      if (r.status !== "PENDING" && r.status !== "RUNNING" && r.status !== "PAUSED") throw new AgentsError(409, "the run has ended, or was asked to stop");
      r.steering = [...(r.steering ?? []), { text, at: now().toISOString() }];
      return r;
    },
    /**
     * A card's answer, as kiff/agents takes it: only the person whose run it is (else 404),
     * only a card of the paused run that still waits (else 409), a confirm's answer one of
     * its options (else 400). Recorded as an `answered` event, and the run goes on.
     */
    async answerCard(tenant, agent, id, card, answer, requester) {
      calls.push(`answerCard ${agent} ${id} ${card} ${JSON.stringify(answer)}`);
      failing("answerCard");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      if (!r || !r.requester || r.requester.kind !== requester.kind || r.requester.id !== requester.id) throw gone();
      const q = r.status === "PAUSED" ? (r.requirements ?? []).find((x) => x.kind === "card" && !x.answered && (x.input as { id?: string })?.id === card) : undefined;
      if (!q) throw new AgentsError(409, "no card of this run waits for an answer by that id");
      const shown = q.input as { type?: string; options?: Array<{ value: string; label: string }> };
      const option = shown.options?.find((o) => o.value === answer);
      if (shown.type === "confirm" && !option) throw new AgentsError(400, `${JSON.stringify(answer)} isn't one of the card's options`);
      const words = option?.label ?? (typeof answer === "string" ? answer : JSON.stringify(answer));
      q.answered = now().toISOString();
      q.result = { answer, words };
      const list = events.get(r.session) ?? [];
      list.push({ session: r.session, run: r.id, seq: list.length + 1, time: now().toISOString(), kind: "answered", body: { id: card, type: shown.type, answer, words } });
      events.set(r.session, list);
      r.status = "RUNNING";
      return r;
    },
    /** A result answered in tafs cloud's name, as kiff/agents takes it: only a result card of the paused run that still waits (else 409). */
    async answerResult(tenant, agent, id, card, outcome) {
      calls.push(`answerResult ${agent} ${id} ${card} ${JSON.stringify(outcome)}`);
      failing("answerResult");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      const q = r?.status === "PAUSED" ? (r.requirements ?? []).find((x) => x.kind === "card" && !x.answered && (x.input as { id?: string; type?: string })?.id === card && (x.input as { type?: string }).type === "result") : undefined;
      if (!r || !q) throw new AgentsError(409, "no card of this run waits for an answer by that id", undefined, "card_not_waiting");
      q.answered = now().toISOString();
      q.result = { answer: outcome, answered_by: "tafs cloud" };
      return r;
    },
    /** What the run's requester may do now: `access` by subject, as KIFF would answer Coach's key; a subject missing from it is KIFF not answering. */
    async requesterAccess(tenant, agent, id) {
      calls.push(`requesterAccess ${agent} ${id}`);
      failing("requesterAccess");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      if (!r) throw gone();
      if (r.requester?.kind !== "member") throw new AgentsError(409, "no person asked for this run", undefined, "no_requester");
      const a = access.get(r.requester.id);
      if (!a) throw new AgentsError(502, "KIFF didn't say what this person may do now", undefined, "kiff_unanswered");
      return { requester: r.requester, use: true, ...a };
    },
    async continueHeld(tenant, agent, exception) {
      calls.push(`continueHeld ${agent} ${exception}`);
      failing("continueHeld");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && (x.requirements ?? []).some((q) => q.exception === exception));
      if (!r) throw gone();
      if (r.status !== "PAUSED") throw new AgentsError(409, "the run isn't paused: read it");
      return r;
    },
    async cancelRun(tenant, agent, id, requester) {
      calls.push(`cancelRun ${agent} ${id}`);
      failing("cancelRun");
      const r = runs.find((x) => x.tenant === tenant && x.agent === agent && x.id === id);
      // Another person's run is not found; tafs cloud itself (no requester) may stop any.
      if (!r || (requester && r.requester && (r.requester.kind !== requester.kind || r.requester.id !== requester.id))) throw gone();
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
  /** A card the run shows and waits on for the owner's answer (kiff/agents' cards wait for their answer). */
  const ask = (run: Run, component: { id: string; type: string } & Record<string, unknown>, at?: string) => {
    show(run, component, at);
    run.status = "PAUSED";
    run.requirements = [...(run.requirements ?? []), { call_id: `call_${component.id}`, tool: "show", input: component, operation: "", expires: "2026-10-23T12:00:00Z", kind: "card" }];
  };
  return { api, calls, spent, aiSent, notified, agents, schedules, runs, sessions, events, notes, fail, say, show, ask, setupDrafts, access, streamed, resumed, versions, trialWrites, switching, versionDefs, halts, trials };
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
    async keep(key) { taken.add(key); },
    async held(key) { return taken.has(key); },
  };
}

/** Archived chats, by tenant and taf (kiff/tafs#82). */
/** Archives tafs cloud kept before kiff/agents did; `add` is a test's, for one kept then. */
export function memoryArchived(): Archived & { held: Map<string, Set<string>>; add(t: string, taf: string, session: string): Promise<void> } {
  const held = new Map<string, Set<string>>();
  return {
    held,
    async list(t, taf) { return [...(held.get(`${t}#${taf}`) ?? [])]; },
    async add(t, taf, session) { held.set(`${t}#${taf}`, new Set([...(held.get(`${t}#${taf}`) ?? []), session])); },
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
