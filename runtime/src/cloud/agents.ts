import { createHash, createHmac, type Hash, type Hmac } from "node:crypto";
import { SignatureV4 } from "@smithy/signature-v4";

/**
 * kiff/agents, as tafs cloud calls it: the runtime that runs each taf
 * (kiff/agents ARCHITECTURE.md). Its server is a Lambda function URL with
 * AWS_IAM auth; tafs cloud's role is its one caller. Every request is signed
 * with that role and names the KIFF account in Kiff-Tenant, after tafs cloud
 * has verified the person: the server trusts the header only from that role.
 *
 * Only what tafs needs, in kiff/agents' own shapes. Runs start in the
 * background, and the app reads the conversation; the one exception is a
 * chat message sent to be streamed (kiff/tafs#60), whose run is held here
 * in the foreground so its words reach the phone as they are written.
 */

export interface Credentials {
  accessKeyId: string;
  secretAccessKey: string;
  sessionToken?: string;
}

export interface AgentsConfig {
  /** The function URL, e.g. https://<id>.lambda-url.eu-west-1.on.aws */
  url: string;
  /** The function's region: what the signature is scoped to. */
  region: string;
  credentials: () => Promise<Credentials>;
  fetch?: typeof fetch;
  now?: () => Date;
}

export class AgentsError extends Error {
  constructor(readonly status: number, message: string, readonly blocking?: BlockingRun[]) {
    super(message);
  }
}

/** A run that keeps a switch from beginning, as kiff/agents names it in the 409 (kiff/tafs#206). */
export interface BlockingRun {
  id: string;
  session: string;
  status: RunStatus;
  trigger: string;
}

/** A model's price, in millionths of a US dollar per million tokens of each kind. */
export interface Price {
  Input: number;
  Output: number;
  CacheRead: number;
  CacheWrite: number;
  /** An hour's cache write: set, kiff/agents keeps the shared prefix an hour (kiff/agents#171). */
  CacheWrite1h?: number;
}

/**
 * How long one kind of event is kept: never (0), until its run ends (1), kept (2) for Retention
 * nanoseconds (0: as long as the session), or, for what a chat read (kept_read), until the chat has
 * been idle for Retention and at most Max after the read (3, kiff/agents#172).
 */
export interface KeepRule {
  Keep: 0 | 1 | 2 | 3;
  Retention: number;
  Max?: number;
}

export const EVENT_KINDS = ["owner_message", "agent_message", "tool_call", "tool_result", "report", "memory_note", "usage", "error"] as const;
export type EventKind = (typeof EVENT_KINDS)[number];

export const KEEP = { never: 0, untilEnd: 1, kept: 2, untilIdle: 3 } as const;

/** The effort levels kiff/agents takes, lowest first. */
export type Effort = "low" | "medium" | "high" | "xhigh" | "max";

/** An agent as stored in kiff/agents' registry. The tenant and id come from the caller and the path. */
export interface AgentDefinition {
  tenant?: string;
  id?: string;
  instructions: string;
  model: string;
  price: Price;
  max_tokens: number;
  /** How hard the model works on each turn ("low" to "max"); absent is the provider's default. */
  effort?: Effort;
  /**
   * "fast": the turns run at the provider's fast output, at price_fast, when someone waits on the
   * run (kiff/agents#197); a conversation keeps one speed. Absent: standard.
   */
  speed?: "standard" | "fast";
  price_fast?: Price;
  gateway: string;
  /** Names the agent's KIFF key in the tenant's secrets; never the key. */
  key_ref: string;
  keep: Record<EventKind, KeepRule> & { kept_read?: KeepRule };
  max_cost_micro_usd: number;
  max_turns?: number;
  history: number;
  /** What those runs' messages may take, in tokens; in a chat, what no longer fits is summarised (kiff/agents#172). */
  history_tokens?: number;
  /** The show tool: UI components in the owner's chat (kiff/agents#91). Off unless set. */
  show?: boolean;
  /** The planque the agent asks for vendor access through (kiff/agents#91). Off unless set. */
  planque?: string;
  /** The draft tool: Setup submits a taf's KIFF draft, its claim secret kept from the model (kiff/agents#94). */
  drafts?: boolean;
  /** Tool name prefixes kept out of the prompt until the model searches for them (kiff/tafs#206). */
  deferred_tools?: string[];
  /**
   * Every gateway tool that isn't KIFF's own (kiff_*) kept out of the prompt until the model searches
   * for it, so a Card that adds a vendor's tools mid-conversation keeps the cache (kiff/agents#197).
   */
  defer_vendor_tools?: boolean;
}

/** The period a chat counts toward for AI fair use, and its bound in micro US dollars (0: none). */
export interface AIBound {
  period: string;
  bound_micro_usd: number;
}

/** Where kiff/agents announces a run when it pauses and when it ends (kiff/tafs#206). */
export interface Notify {
  url: string;
  /** tafs cloud's own value for the task: a note with a ref it doesn't hold is dropped. */
  ref: string;
}

export type RunStatus = "PENDING" | "RUNNING" | "PAUSED" | "COMPLETED" | "ERROR" | "CANCELLED";

/** One call KIFF holds for the owner: what a paused run waits for. */
export interface Requirement {
  call_id: string;
  tool: string;
  input: unknown;
  operation: string;
  exception?: string;
  expires: string;
  answered?: string;
  answer?: string;
}

export interface Run {
  id: string;
  agent: string;
  session: string;
  trigger: string;
  source?: string;
  status: RunStatus;
  stop?: string;
  output?: string;
  error?: string;
  cost_micro_usd: number;
  turns: number;
  requirements?: Requirement[];
  cancel_requested?: boolean;
  /** What the owner wrote to the run while it went (kiff/agents#102), each marked once the run took it. */
  steering?: Array<{ text: string; at: string; taken?: boolean }>;
  /** Who asked for it; none for a job's run. */
  requester?: Requester;
  /** The run opened its session: a requester's live chat had gone quiet, or none was asked for. */
  new_session?: boolean;
  created: string;
  updated: string;
}

/** One version of an agent's definition (kiff/tafs#107): any one can be live. */
export interface AgentVersion {
  n: number;
  /** What changed, in plain words. */
  note: string;
  /** Who made it: the owner's subject, or "setup". */
  by: string;
  created: string;
  digest: string;
  live: boolean;
  /** The KIFF Card revisions this version needs live; empty: the agent's current Cards. */
  revisions?: string[];
  /** The KIFF draft whose confirmation brings those revisions, while it isn't confirmed. */
  draft?: string;
  /** The KIFF Cards it ran under, each at its revision when it went live (kiff/agents#142). */
  cards?: CardRevision[];
  /** Its trials that ended, oldest first (kiff/tafs#173). */
  trials?: TrialSummary[];
}

/** An agent's open trial of version n (kiff/tafs#173). */
export interface Trial {
  id: string;
  n: number;
  /** The KIFF draft trying as its Card. */
  draft: string;
  /** Its chat, which kiff/agents opens with the trial (kiff/agents#215). */
  session?: string;
  started: string;
  /** When the owner last wrote in it: unused for 7 days, it ends. */
  used: string;
}

/** What a version keeps of an ended trial. */
export interface TrialSummary {
  started: string;
  ended: string;
  why: "installed" | "back" | "idle";
  calls: number;
  held: number;
  refused: number;
}

/** One KIFF Card at one revision. */
export interface CardRevision {
  id: string;
  revision: number;
}

/** A switch to version n under way (kiff/agents#137): nothing of the agent runs until it ends. */
export interface Switching {
  n: number;
  /** The KIFF draft the switch waits for. */
  draft?: string;
  at: string;
}

/**
 * An agent's halt, or its account's, as kiff/agents keeps it: while
 * halted nothing of it runs, and nothing from before the halt ever does. `own` and `tenant` say
 * which halt holds. A halt answers how many runs it cancelled.
 */
export interface AgentsHalt {
  agent?: string;
  halted: boolean;
  own: boolean;
  tenant: boolean;
  halted_at?: string;
  stopped?: number;
  unstopped?: number;
}

/** An agent's versions, and the switch under way. */
export interface VersionState {
  versions: AgentVersion[];
  switching?: Switching;
}

/** A trial write: what KIFF would decide, nothing sent (kiff/tafs#107). */
export interface TrialWrite {
  tool: string;
  title?: string;
  arguments: unknown;
  decision: "allowed" | "held" | "refused";
  reason?: string;
}

export interface Session {
  id: string;
  agent: string;
  /** A trial's is "trial" (kiff/tafs#107, #173), apart from the taf's runs and chats. */
  kind: "run" | "chat" | "trial";
  started: string;
  updated: string;
  expires?: string;
  /** Whose thread it is, as "member:<subject>" or "door:<id>"; none for a job's, or one from before owners. */
  owner?: string;
  /** Its owner put it away: it leaves their list, and a live message starts a new chat. */
  archived?: boolean;
}

/** Who asks for a run: a member of the account, by their KIFF subject, a door's sender, or an agent asking on its own. */
export interface Requester {
  kind: "member" | "door" | "agent";
  id: string;
}

/**
 * Where a run goes: the named session, else the requester's live chat when `live`
 * (kiff/agents picks it), else a new session, the requester's.
 */
export interface RunAt {
  session?: string;
  requester?: Requester;
  live?: boolean;
  /** The account's run grant for a member's run: kiff/agents takes the requester from it. */
  grant?: string;
}

/**
 * How tafs cloud reads or writes a person's content in kiff/agents: with the person named,
 * or, for what no person asked (a door's chat, Coach's checks, a job), as the service itself,
 * by name. kiff/agents refuses a call that says neither, so a missing person never means everyone.
 */
const AS_SERVICE = { as: "service" } as const;

/** The query that names the person, or says the service acts. */
function as(owner?: string): Record<string, string> {
  return owner ? { owner } : { ...AS_SERVICE };
}

/** A run's start body for where it goes. */
function runAt(at: RunAt): Record<string, unknown> {
  return {
    ...(at.session ? { session_id: at.session } : {}),
    ...(at.requester ? { requester: at.requester } : {}),
    ...(at.live && !at.session ? { chat: "live" } : {}),
    ...(at.grant ? { run_grant: at.grant } : {}),
  };
}

export interface SessionEvent {
  session: string;
  run: string;
  seq: number;
  time: string;
  /**
   * A kind the keep rules name, a `component` the agent showed (kiff/agents#91), or
   * a `call_ref`: what a call through KIFF acted on, kept like messages (kiff/tafs#167).
   */
  kind: EventKind | "component" | "call_ref";
  body: unknown;
}

export interface Note {
  agent: string;
  /** Whose note it is: none for a shared one, read by every run; else a requester's, as "member:<subject>". */
  scope?: string;
  name: string;
  text: string;
  updated: string;
}

export interface Schedule {
  tenant?: string;
  id: string;
  agent: string;
  /** Five-field cron, read in time_zone (IANA; empty is UTC). */
  cron: string;
  time_zone?: string;
  input: string;
  enabled: boolean;
  /** The next firing, in UTC; absent when it's off. */
  next?: string;
}

/** The ids kiff/agents takes: 1 to 128 letters, digits, '-', '_' or '.', never "." or "..". */
const ID = /^[A-Za-z0-9._-]{1,128}$/;

export class Agents {
  private readonly signer: SignatureV4;
  private readonly base: URL;

  constructor(private readonly cfg: AgentsConfig) {
    this.base = new URL(cfg.url);
    if (this.base.protocol !== "https:") throw new Error("kiff/agents is called over https");
    this.signer = new SignatureV4({ service: "lambda", region: cfg.region, credentials: cfg.credentials, sha256: Sha256 });
  }

  // Agents.

  putAgent(tenant: string, id: string, def: AgentDefinition): Promise<AgentDefinition> {
    return this.call("PUT", tenant, ["agents", id], def);
  }

  // Versions (kiff/tafs#107).

  /** The agent's versions, oldest first, and the switch under way, if any. */
  async versions(tenant: string, id: string): Promise<VersionState> {
    const r = await this.call<{ versions: AgentVersion[]; switching?: Switching }>("GET", tenant, ["agents", id, "versions"]);
    return { versions: r.versions, ...(r.switching ? { switching: r.switching } : {}) };
  }

  /**
   * Begins the switch to version n, waiting for `draft`'s confirmation: from
   * then on nothing of the agent runs (kiff/agents#137). 409 while a run of it
   * is going or waiting, or another switch is under way.
   */
  async beginSwitch(tenant: string, id: string, n: number, draft?: string): Promise<Switching> {
    return (await this.call<{ switching: Switching }>("POST", tenant, ["agents", id, "versions", String(n), "pending"], draft ? { draft } : {})).switching;
  }

  /**
   * Puts `draft` in place of the one the switch to version n under way waits
   * for, keeping its mark, so nothing of the agent runs in between (the
   * version's Cards drafted again, kiff/agents#150). 409 when it isn't
   * switching to n.
   */
  async replaceSwitchDraft(tenant: string, id: string, n: number, draft: string): Promise<Switching> {
    return (await this.call<{ switching: Switching }>("POST", tenant, ["agents", id, "versions", String(n), "pending"], { draft, replace: true })).switching;
  }

  /** Drops the switch under way; the live version stays. True when there was one. */
  async cancelSwitch(tenant: string, id: string): Promise<boolean> {
    return (await this.call<{ cancelled: boolean }>("DELETE", tenant, ["agents", id, "switching"])).cancelled;
  }

  /**
   * Stores the agent's next version (kiff/tafs#107): its definition, what
   * changed, who made it, and the KIFF draft revising its Cards, if any.
   * What's live doesn't change. Returns its number.
   */
  async createVersion(tenant: string, id: string, v: { definition: AgentDefinition; note: string; by: string; draft?: string }): Promise<number> {
    return (await this.call<{ n: number }>("POST", tenant, ["agents", id, "versions"], v)).n;
  }

  /** One version, with its definition (kiff/agents#141). */
  async getVersion(tenant: string, id: string, n: number): Promise<{ version: AgentVersion; definition: AgentDefinition }> {
    return this.call("GET", tenant, ["agents", id, "versions", String(n)]);
  }

  /** Makes version n live, forward or back, recording the KIFF Cards it runs under, if given. */
  async switchVersion(tenant: string, id: string, n: number, cards?: CardRevision[]): Promise<void> {
    await this.call("POST", tenant, ["agents", id, "versions", String(n), "live"], cards?.length ? { cards } : {});
  }

  /** Starts a trial of version n: a run whose writes KIFF decides without sending. */
  async startTrial(tenant: string, id: string, n: number, input: string, notify?: Notify): Promise<Run> {
    return (await this.call<{ run: Run }>("POST", tenant, ["agents", id, "versions", String(n), "trial"], { input, ...(notify ? { notify } : {}) })).run;
  }

  /** A trial so far: the run, and what KIFF decided for each write. */
  async trial(tenant: string, id: string, run: string): Promise<{ run: Run; writes: TrialWrite[] }> {
    return this.call("GET", tenant, ["agents", id, "trials", run], undefined, { ...AS_SERVICE });
  }

  // The open trial (kiff/tafs#173): one chat with a version, under its trial Card.

  /** Opens the trial of version n under `draft`, the KIFF draft trying as its Card, as `requester`'s own. 409 while another is open. */
  async openTrial(tenant: string, id: string, n: number, draft: string, requester?: Requester): Promise<Trial> {
    return (await this.call<{ trial: Trial }>("PUT", tenant, ["agents", id, "trial"], { n, draft, ...(requester ? { requester } : {}) })).trial;
  }

  /** The agent's open trial; undefined when it has none. */
  async openedTrial(tenant: string, id: string): Promise<Trial | undefined> {
    return (await this.call<{ trial: Trial }>("GET", tenant, ["agents", id, "trial"]).catch(notFound))?.trial;
  }

  /** One message to the trial, from `requester`: a turn of version n in the trial's chat, in the background. */
  async trialMessage(tenant: string, id: string, input: string, requester?: Requester): Promise<{ run: Run; trial: Trial }> {
    return this.call("POST", tenant, ["agents", id, "trial", "messages"], { input, ...(requester ? { requester } : {}) });
  }

  /** Ends the trial with KIFF's counts: its chat is deleted, and its version keeps one line. */
  async endTrial(tenant: string, id: string, why: "installed" | "back", counts: { calls: number; held: number; refused: number }): Promise<TrialSummary> {
    // Only the counts: KIFF's trial also carries its status, which kiff/agents refuses.
    const { calls, held, refused } = counts;
    return (await this.call<{ ended: TrialSummary }>("DELETE", tenant, ["agents", id, "trial"], { why, calls, held, refused })).ended;
  }

  /** The agent's definition; undefined when the tenant has none of that id. */
  async getAgent(tenant: string, id: string): Promise<AgentDefinition | undefined> {
    return this.call<AgentDefinition>("GET", tenant, ["agents", id]).catch(notFound);
  }

  /** Removes the agent and everything kept for it, its held calls withdrawn first. Already gone counts as done. */
  async deleteAgent(tenant: string, id: string): Promise<void> {
    await this.call("DELETE", tenant, ["agents", id]).catch(notFound);
  }

  /**
   * Erases everything kiff/agents keeps for the account, when its owner
   * deletes it (kiff/tafs#185): every agent as deleteAgent removes it, the
   * account's own halt and skills, its sealing key and its drafts' records.
   * Safe to repeat.
   */
  async eraseTenant(tenant: string): Promise<void> {
    await this.call("DELETE", tenant, ["tenant"]);
  }

  // Runs.

  /**
   * Starts a run of the agent in the background, where `at` says.
   * With `ai`, a chat counted toward the period's AI fair use, refused (409) past its bound.
   * A requester past their flood guard is 429.
   */
  startRun(tenant: string, agent: string, input: string, at: RunAt = {}, ai?: AIBound, notify?: Notify): Promise<Run> {
    return this.call("POST", tenant, ["agents", agent, "runs"], {
      input, ...runAt(at), ...(ai ? { ai } : {}), ...(notify ? { notify } : {}), background: true,
    }, at.requester || at.grant ? undefined : { ...AS_SERVICE });
  }

  /**
   * The agent's runs, newest first, without their paused turn; `active`, only
   * the ones not ended: what it is doing and waiting on (kiff/tafs#206).
   */
  async runs(tenant: string, agent: string, active = false, limit = 20): Promise<Run[]> {
    const out = await this.call<{ runs: Run[] }>("GET", tenant, ["agents", agent, "runs"], undefined, { limit: String(limit), ...(active ? { active: "true" } : {}), ...AS_SERVICE });
    return out.runs;
  }

  /**
   * Starts a run in the foreground and reads its stream (server-sent events):
   * the run first, what the agent does as it does it, then where it stopped.
   * A refusal before the stream begins throws, as `startRun` does. The run is
   * kiff/agents' invocation's, not this reader's: stopping reading stops none of it.
   */
  async *streamRun(tenant: string, agent: string, input: string, at: RunAt = {}, ai?: AIBound): AsyncGenerator<RunStreamEvent> {
    const res = await this.send("POST", tenant, ["agents", agent, "runs"], { input, ...runAt(at), ...(ai ? { ai } : {}) },
      at.requester || at.grant ? undefined : { ...AS_SERVICE }, "text/event-stream");
    if (!res.ok) throw await failure(res);
    if (!res.body) throw new AgentsError(502, "kiff/agents answered with no stream");
    yield* readEvents(res.body);
  }

  /** What the account's counted chats cost the model in the period, in micro US dollars (kiff/tafs#193). */
  async aiSpent(tenant: string, period: string): Promise<number> {
    const p = await this.call<{ spent_micro_usd?: number }>("GET", tenant, ["usage", "ai"], undefined, { period });
    return p.spent_micro_usd ?? 0;
  }

  /** A run, read as the service: tafs cloud checks whose it is before showing what it said. */
  getRun(tenant: string, agent: string, run: string): Promise<Run> {
    return this.call("GET", tenant, ["agents", agent, "runs", run], undefined, { ...AS_SERVICE });
  }

  /**
   * Steers a run that hasn't ended (kiff/agents#102): the owner's message, taken at
   * the run's next checkpoint. A run that ended, or was asked to stop, is 409.
   * With `requester`, only their own run, counted toward their flood guard (429);
   * without, tafs cloud itself writes.
   */
  steerRun(tenant: string, agent: string, run: string, text: string, requester?: Requester): Promise<Run> {
    return this.call("POST", tenant, ["agents", agent, "runs", run, "messages"], { text, ...(requester ? { requester } : {}) }, requester ? undefined : { ...AS_SERVICE });
  }

  /**
   * Continues a paused run whose held calls KIFF has resolved, in the background
   * (202). One that isn't paused, or still waits for KIFF, is 409: the sweep or
   * another continue has it.
   */
  continueRun(tenant: string, agent: string, run: string): Promise<Run> {
    return this.call("POST", tenant, ["agents", agent, "runs", run, "continue"], { background: true }, { ...AS_SERVICE });
  }

  /**
   * Continues, in the background, the run that paused on the held call KIFF
   * names `exception`, once KIFF has answered it (kiff/tafs#206): kiff/agents
   * asks KIFF itself, so this only has the run go on sooner. 409 while KIFF
   * still holds a call of the run, or once it went on; 404 when no run of the
   * agent paused on that call.
   */
  continueHeld(tenant: string, agent: string, exception: string): Promise<Run> {
    return this.call("POST", tenant, ["agents", agent, "holds", exception, "continue"], { background: true }, { ...AS_SERVICE });
  }

  /**
   * Cancels a run; a paused one has its held calls withdrawn in KIFF first. With
   * `requester`, only their own run; without, tafs cloud itself stops it.
   */
  cancelRun(tenant: string, agent: string, run: string, requester?: Requester): Promise<Run> {
    return this.call("POST", tenant, ["agents", agent, "runs", run, "cancel"], requester ? { requester } : undefined, requester ? undefined : { ...AS_SERVICE });
  }

  /**
   * Claims the key of a taf's draft that the setup agent submitted (kiff/agents#94):
   * kiff/agents takes it from KIFF with the claim secret it keeps sealed, and writes it
   * under keyRef, where the taf's definition will name it. The key never comes here.
   * The same keyRef again answers the same; another is 409.
   */
  claimTafDraft(tenant: string, draftID: string, keyRef: string): Promise<{ agent_id: string }> {
    return this.call("POST", tenant, ["drafts", draftID, "claim"], { key_ref: keyRef });
  }

  // Sessions and memory.

  /** The agent's sessions, newest first: with `owner`, theirs and the taf's jobs'; without, as the service. */
  async sessions(tenant: string, agent: string, limit = 20, owner?: string): Promise<Session[]> {
    const out = await this.call<{ sessions: Session[] }>("GET", tenant, ["sessions"], undefined, { agent_id: agent, limit: String(limit), ...as(owner) });
    return out.sessions;
  }

  /** A session and its events still kept, in order: read as `owner`, or as the service. */
  session(tenant: string, id: string, owner?: string): Promise<{ session: Session; events: SessionEvent[] }> {
    return this.call("GET", tenant, ["sessions", id], undefined, as(owner));
  }

  /** Archives a session, or puts it back: with `owner`, only one that is theirs. */
  async archive(tenant: string, id: string, archived: boolean, owner?: string): Promise<Session> {
    const out = await this.call<{ session: Session }>("POST", tenant, ["sessions", id, archived ? "archive" : "unarchive"], undefined, as(owner));
    return out.session;
  }

  /** The agent's notes: with `owner`, the shared ones and that person's own; without, every note, as the service. */
  async memories(tenant: string, agent: string, owner?: string): Promise<Note[]> {
    return (await this.call<{ memories: Note[] }>("GET", tenant, ["agents", agent, "memories"], undefined, as(owner))).memories;
  }

  /** Forgets one note. Already gone counts as done. */
  /** Forgets a note: with `scope`, that requester's own, as them; without, the shared one of that name, as the service. */
  async forget(tenant: string, agent: string, name: string, scope?: string): Promise<void> {
    if (!name || name === "." || name === "..") throw new AgentsError(404, "no such note");
    await this.call("DELETE", tenant, ["agents", agent, "memories", { text: name }], undefined, scope ? { scope, owner: scope } : { ...AS_SERVICE }).catch(notFound);
  }

  // Halts. No agent is the account itself.

  /** Halts the agent, or with none the whole account; its active runs are cancelled. */
  halt(tenant: string, agent?: string): Promise<AgentsHalt> {
    return this.call("POST", tenant, agent ? ["agents", agent, "halt"] : ["halt"]);
  }

  /** Ends the agent's halt, or the account's; what came before it still never runs. */
  resume(tenant: string, agent?: string): Promise<AgentsHalt> {
    return this.call("POST", tenant, agent ? ["agents", agent, "resume"] : ["resume"]);
  }

  /** The agent's halt over its account's, or the account's own. */
  halted(tenant: string, agent?: string): Promise<AgentsHalt> {
    return this.call("GET", tenant, agent ? ["agents", agent, "halt"] : ["halt"]);
  }

  // Schedules.

  createSchedule(tenant: string, s: Schedule): Promise<Schedule> {
    return this.call("POST", tenant, ["schedules"], writable(s));
  }

  /** The schedule; undefined when the tenant has none of that id. */
  async getSchedule(tenant: string, id: string): Promise<Schedule | undefined> {
    return this.call<Schedule>("GET", tenant, ["schedules", id]).catch(notFound);
  }

  /** Replaces the schedule; a schedule as read (with its `next`) can be sent back as it is. */
  updateSchedule(tenant: string, s: Schedule): Promise<Schedule> {
    return this.call("PUT", tenant, ["schedules", s.id], writable(s));
  }

  switchSchedule(tenant: string, id: string, on: boolean): Promise<Schedule> {
    return this.call("POST", tenant, ["schedules", id, on ? "enable" : "disable"]);
  }

  /** Fires the schedule now, by hand: an ordinary background run of its agent. */
  trigger(tenant: string, id: string): Promise<Run> {
    return this.call("POST", tenant, ["schedules", id, "trigger"]);
  }

  /** The runs the schedule started, newest first. */
  async scheduleRuns(tenant: string, id: string, limit = 20): Promise<Run[]> {
    return (await this.call<{ runs: Run[] }>("GET", tenant, ["schedules", id, "runs"], undefined, { limit: String(limit), ...AS_SERVICE })).runs;
  }

  /**
   * One signed request. Each path segment is an id, checked, so a value from
   * a route (a taf's id) can't reach another path; or free text (a note's
   * name), escaped into one segment.
   */
  private async call<T>(method: string, tenant: string, segments: Array<string | { text: string }>, body?: unknown, query?: Record<string, string>,
    extra?: Record<string, string>): Promise<T> {
    const res = await this.send(method, tenant, segments, body, query, "application/json", extra);
    if (res.status === 204) return undefined as T;
    if (!res.ok) throw await failure(res);
    const text = await res.text();
    try { return (text ? JSON.parse(text) : undefined) as T; } catch { return undefined as T; }
  }

  /** One request, signed with this function's role. */
  private async send(method: string, tenant: string, segments: Array<string | { text: string }>, body?: unknown, query?: Record<string, string>,
    accept = "application/json", extra?: Record<string, string>): Promise<Response> {
    if (!tenant) throw new AgentsError(401, "no KIFF account to call kiff/agents for");
    const path: string[] = [];
    for (const seg of segments) {
      if (typeof seg !== "string") { path.push(encodeURIComponent(seg.text)); continue; }
      if (!ID.test(seg) || seg === "." || seg === "..") throw new AgentsError(404, "not found");
      path.push(seg);
    }
    const payload = body === undefined ? undefined : JSON.stringify(body);
    const headers: Record<string, string> = { ...extra, host: this.base.host, accept, "kiff-tenant": tenant };
    if (payload !== undefined) headers["content-type"] = "application/json";
    const signed = await this.signer.sign(
      {
        method,
        protocol: "https:",
        hostname: this.base.hostname,
        // The signer escapes it once more: a function URL's canonical path is escaped twice.
        path: "/" + path.join("/"),
        headers,
        ...(query ? { query } : {}),
        ...(payload !== undefined ? { body: payload } : {}),
      },
      { signingDate: this.cfg.now?.() ?? new Date() },
    );
    const url = new URL("/" + path.join("/"), this.base);
    for (const [k, v] of Object.entries(query ?? {})) url.searchParams.set(k, v);
    // fetch sets Host itself, from the URL; it is signed with the same value.
    const { host: _host, ...signedHeaders } = signed.headers;
    return (this.cfg.fetch ?? fetch)(url, { method, headers: signedHeaders, ...(payload !== undefined ? { body: payload } : {}) });
  }
}

/** kiff/agents' refusal, as an error with its status and words. */
async function failure(res: Response): Promise<AgentsError> {
  const text = await res.text().catch(() => "");
  let msg: unknown;
  let blocking: unknown;
  try { ({ error: msg, blocking } = JSON.parse(text) as { error?: unknown; blocking?: unknown }); } catch { msg = undefined; }
  const runs = Array.isArray(blocking)
    ? blocking.filter((b): b is BlockingRun => typeof b?.id === "string" && typeof b?.session === "string")
      .map(({ id, session, status, trigger }) => ({ id, session, status, trigger }))
    : undefined;
  return new AgentsError(res.status, typeof msg === "string" && msg ? msg : `kiff/agents answered ${res.status}`, runs);
}

/**
 * One event of a run's stream (kiff/agents server/runs.go): `run` (the run,
 * first), `event` (a recorded session event), `delta` (what the agent does as
 * it does it: text, tool_call, tool_result, component) and `end` (the run at
 * its stop).
 */
export interface RunStreamEvent {
  event: string;
  data: unknown;
}

/** Reads server-sent events from a stream: each block's event name and its one line of JSON. */
export async function* readEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<RunStreamEvent> {
  const decoder = new TextDecoder();
  let buffer = "";
  for await (const chunk of body as unknown as AsyncIterable<Uint8Array>) {
    buffer += decoder.decode(chunk, { stream: true });
    let cut: number;
    while ((cut = buffer.indexOf("\n\n")) >= 0) {
      const block = buffer.slice(0, cut);
      buffer = buffer.slice(cut + 2);
      const parsed = parseBlock(block);
      if (parsed) yield parsed;
    }
  }
  const last = parseBlock(buffer + decoder.decode());
  if (last) yield last;
}

function parseBlock(block: string): RunStreamEvent | undefined {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return undefined;
  try { return { event, data: JSON.parse(data.join("\n")) }; } catch { return undefined; }
}

/**
 * The fields of a schedule kiff/agents takes. It refuses unknown fields, and
 * `next` is only ever its answer; the tenant comes from the caller.
 */
function writable(s: Schedule): Omit<Schedule, "next" | "tenant"> {
  return { id: s.id, agent: s.agent, cron: s.cron, ...(s.time_zone ? { time_zone: s.time_zone } : {}), input: s.input, enabled: s.enabled };
}

function notFound(err: unknown): undefined {
  if (err instanceof AgentsError && err.status === 404) return undefined;
  throw err;
}

type SourceData = string | ArrayBuffer | ArrayBufferView;

function bytes(d: SourceData): string | Uint8Array {
  if (typeof d === "string") return d;
  if (d instanceof ArrayBuffer) return new Uint8Array(d);
  return new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
}

/** SHA-256 and its HMAC on node:crypto, in the shape the signer takes. */
class Sha256 {
  private h: Hash | Hmac;
  private readonly secret: string | Uint8Array | undefined;
  constructor(secret?: SourceData) {
    this.secret = secret === undefined ? undefined : bytes(secret);
    this.h = this.fresh();
  }
  private fresh(): Hash | Hmac {
    return this.secret === undefined ? createHash("sha256") : createHmac("sha256", this.secret);
  }
  update(data: SourceData): void {
    this.h.update(bytes(data));
  }
  async digest(): Promise<Uint8Array> {
    return new Uint8Array(this.h.digest());
  }
  reset(): void {
    this.h = this.fresh();
  }
}

/** Credentials from the environment: what Lambda gives a function's role. */
export async function envCredentials(): Promise<Credentials> {
  const accessKeyId = process.env.AWS_ACCESS_KEY_ID ?? "";
  const secretAccessKey = process.env.AWS_SECRET_ACCESS_KEY ?? "";
  if (!accessKeyId || !secretAccessKey) throw new Error("no AWS credentials in the environment to sign kiff/agents requests with");
  const sessionToken = process.env.AWS_SESSION_TOKEN;
  return { accessKeyId, secretAccessKey, ...(sessionToken ? { sessionToken } : {}) };
}
