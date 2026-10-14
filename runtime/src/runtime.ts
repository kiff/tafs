import type Anthropic from "@anthropic-ai/sdk";
import {
  META,
  agentParams,
  credentialParams,
  deploymentParams,
  environmentParams,
  ENVIRONMENT_NAME,
} from "./config.js";
import { JOBS, type Job, jobById } from "./jobs.js";

type Agent = Anthropic.Beta.Agents.BetaManagedAgentsAgent;
type Deployment = Anthropic.Beta.BetaManagedAgentsDeployment;
type Environment = Anthropic.Beta.Environments.BetaEnvironment;

export interface Setup {
  environmentID: string;
  /** Agent id by job id. */
  agents: Record<string, string>;
}

/**
 * Makes sure the environment, one agent per job, and every taf's deployment
 * match this code. Safe to run again: it finds what it made by name and
 * metadata, compares the whole configuration it owns, and changes only what
 * differs. An unchanged second run changes nothing.
 */
export async function setup(client: Anthropic, log: (line: string) => void = () => {}): Promise<Setup> {
  let environment: Environment | undefined;
  for await (const env of client.beta.environments.list()) {
    if (env.name === ENVIRONMENT_NAME && !env.archived_at) { environment = env; break; }
  }
  let environmentID: string;
  if (!environment) {
    environmentID = (await client.beta.environments.create(environmentParams())).id;
    log(`created environment ${environmentID}`);
  } else {
    environmentID = environment.id;
    if (environmentChanged(environment)) {
      await client.beta.environments.update(environmentID, { config: environmentParams().config });
      log(`restored environment ${environmentID} to its checked-in restrictions`);
    }
  }

  const existing = new Map<string, Agent>();
  for await (const agent of client.beta.agents.list()) {
    const job = agent.metadata?.[META.job];
    if (job && !agent.archived_at && !existing.has(job)) existing.set(job, agent);
  }

  const agents: Record<string, string> = {};
  const versions = new Map<string, number>();
  for (const job of JOBS) {
    const want = agentParams(job);
    const have = existing.get(job.id);
    if (!have) {
      const made = await client.beta.agents.create(want);
      log(`created ${job.id} agent ${made.id}`);
      agents[job.id] = made.id;
      versions.set(made.id, made.version);
    } else if (agentChanged(have, job)) {
      const updated = await client.beta.agents.update(have.id, {
        version: have.version,
        name: want.name,
        description: want.description,
        model: want.model,
        system: want.system,
        mcp_servers: want.mcp_servers,
        tools: want.tools,
        skills: [],
      });
      log(`updated ${job.id} agent ${have.id} to version ${updated.version}`);
      agents[job.id] = have.id;
      versions.set(have.id, updated.version);
    } else {
      agents[job.id] = have.id;
      versions.set(have.id, have.version);
    }
  }

  // A deployment keeps the agent version it was created or last updated with,
  // so move every taf onto its job agent's current version. Only `agent` is
  // sent: the taf's vault, schedule, budget and paused state stay as they are.
  for await (const d of client.beta.deployments.list()) {
    if (!d.metadata?.[META.taf] || d.archived_at) continue;
    const version = versions.get(d.agent.id);
    if (version === undefined || d.agent.version === version) continue;
    await client.beta.deployments.update(d.id, { agent: { type: "agent", id: d.agent.id, version } });
    log(`moved taf ${d.name} to agent version ${version}`);
  }

  return { environmentID, agents };
}

function effortName(effort: unknown): string | null {
  if (typeof effort === "string") return effort;
  if (effort && typeof effort === "object" && "type" in effort) return String((effort as { type: unknown }).type);
  return null;
}

interface OwnedFields {
  system?: string | null;
  description?: string | null;
  model: unknown;
  mcp_servers?: ReadonlyArray<{ name: string; url: string }> | null;
  tools?: ReadonlyArray<unknown> | null;
  skills?: ReadonlyArray<unknown> | null;
}

interface ToolFields {
  type: string;
  mcp_server_name?: string;
  default_config?: { enabled?: boolean | null; permission_policy?: { type: string } | null } | null;
  configs?: ReadonlyArray<{ name: string; enabled?: boolean | null; permission_policy?: { type: string } | null }> | null;
}

/**
 * The configuration tafs owns on an agent, in one comparable shape. The API
 * echoes defaults (an MCP toolset is enabled and always_ask unless set) and
 * effort as an object, so both sides are normalized the same way.
 */
function ownedConfig(a: OwnedFields): string {
  const model = typeof a.model === "string" ? { id: a.model } : (a.model as { id: string; effort?: unknown });
  const tools = (a.tools ?? []).map((t) => {
    const tool = t as ToolFields;
    if (tool.type !== "mcp_toolset") return { type: tool.type };
    return {
      type: tool.type,
      server: tool.mcp_server_name,
      enabled: tool.default_config?.enabled ?? true,
      policy: tool.default_config?.permission_policy?.type ?? "always_ask",
      configs: (tool.configs ?? []).map((c) => ({
        name: c.name,
        enabled: c.enabled ?? null,
        policy: c.permission_policy?.type ?? null,
      })),
    };
  });
  return JSON.stringify({
    system: a.system ?? null,
    description: a.description ?? null,
    model: { id: model.id, effort: effortName(model.effort) },
    mcp_servers: [...(a.mcp_servers ?? [])]
      .map((m) => ({ name: m.name, url: m.url.replace(/\/+$/, "") }))
      .sort((x, y) => x.name.localeCompare(y.name)),
    tools,
    skills: (a.skills ?? []).length,
  });
}

/** Whether an agent differs from its job in anything tafs owns. */
export function agentChanged(agent: Agent, job: Job): boolean {
  return ownedConfig(agent as unknown as OwnedFields) !== ownedConfig(agentParams(job) as unknown as OwnedFields);
}

/** Whether the environment named "tafs" lacks the checked-in restrictions. */
export function environmentChanged(env: Environment): boolean {
  const c = env.config;
  if (c.type !== "cloud") return true;
  const n = c.networking;
  return (
    n.type !== "limited" ||
    n.allow_package_managers !== false ||
    n.allow_mcp_servers !== true ||
    (n.allowed_hosts ?? []).length > 0
  );
}

export interface StartTaf {
  name: string;
  job: string;
  /** The taf's own KIFF agent key: its Card. */
  kiffKey: string;
  cron?: string;
  timezone?: string;
  budgetCents?: number;
  metadata?: Record<string, string>;
}

/**
 * Starts a taf: a vault holding its KIFF key, and a deployment that runs its
 * job's agent on a schedule with that vault. Runs once now as well.
 */
export async function startTaf(client: Anthropic, s: Setup, spec: StartTaf): Promise<{ deployment: Deployment; sessionID: string }> {
  const job = jobById(spec.job);
  const agentID = s.agents[job.id];
  if (!agentID) throw new Error(`no agent for ${job.id}; run setup first`);

  const vault = await client.beta.vaults.create({
    display_name: `tafs · ${spec.name}`,
    metadata: { ...spec.metadata, [META.taf]: spec.name, [META.job]: job.id },
  });
  await client.beta.vaults.credentials.create(vault.id, credentialParams(spec.kiffKey));

  const deployment = await client.beta.deployments.create(
    deploymentParams({
      name: spec.name,
      job,
      agentID,
      environmentID: s.environmentID,
      vaultID: vault.id,
      cron: spec.cron,
      timezone: spec.timezone,
      budgetCents: spec.budgetCents,
      metadata: spec.metadata,
    }),
  );
  const run = await client.beta.deployments.run(deployment.id);
  if (!run.session_id) {
    const why = run.error ? `${run.error.type}: ${run.error.message}` : "no session was created";
    throw new Error(
      `taf "${spec.name}" is created (deployment ${deployment.id}), but its first run did not start (${why}). ` +
        `Retry it with: tafs run --name "${spec.name}"`,
    );
  }
  return { deployment, sessionID: run.session_id };
}

export async function listTafs(client: Anthropic): Promise<Deployment[]> {
  const tafs: Deployment[] = [];
  for await (const d of client.beta.deployments.list()) {
    if (d.metadata?.[META.taf]) tafs.push(d);
  }
  return tafs;
}

export async function findTaf(client: Anthropic, name: string): Promise<Deployment> {
  const taf = (await listTafs(client)).find((d) => d.metadata?.[META.taf] === name);
  if (!taf) throw new Error(`no taf named "${name}"`);
  return taf;
}

/** Streams a run's progress until it stops. Returns the agent's last words. */
export async function watch(client: Anthropic, sessionID: string, out: (text: string) => void): Promise<string> {
  let last = "";
  const stream = await client.beta.sessions.events.stream(sessionID);
  for await (const event of stream) {
    switch (event.type) {
      case "agent.message":
        for (const block of event.content) {
          if (block.type === "text") { out(block.text); last = block.text; }
        }
        break;
      case "agent.mcp_tool_use":
        out(`→ ${event.name}`);
        break;
      case "session.error":
        out(`error: ${JSON.stringify(event.error ?? event)}`);
        break;
      case "session.status_idle":
      case "session.status_terminated":
        return last;
    }
  }
  return last;
}
