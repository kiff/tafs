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

export interface Setup {
  environmentID: string;
  /** Agent id by job id. */
  agents: Record<string, string>;
}

/**
 * Makes sure the environment and one agent per job exist and match this
 * code. Safe to run again: it finds what it made by name and metadata, and
 * updates an agent (a new version) only when its prompt or model changed.
 */
export async function setup(client: Anthropic, log: (line: string) => void = () => {}): Promise<Setup> {
  let environmentID: string | undefined;
  for await (const env of client.beta.environments.list()) {
    if (env.name === ENVIRONMENT_NAME) { environmentID = env.id; break; }
  }
  if (!environmentID) {
    environmentID = (await client.beta.environments.create(environmentParams())).id;
    log(`created environment ${environmentID}`);
  }

  const existing = new Map<string, Agent>();
  for await (const agent of client.beta.agents.list()) {
    const job = agent.metadata?.[META.job];
    if (job && !agent.archived_at && !existing.has(job)) existing.set(job, agent);
  }

  const agents: Record<string, string> = {};
  for (const job of JOBS) {
    const want = agentParams(job);
    const have = existing.get(job.id);
    if (!have) {
      const made = await client.beta.agents.create(want);
      log(`created ${job.id} agent ${made.id}`);
      agents[job.id] = made.id;
    } else if (agentChanged(have, job)) {
      const updated = await client.beta.agents.update(have.id, {
        version: have.version,
        name: want.name,
        description: want.description,
        model: want.model,
        system: want.system,
        mcp_servers: want.mcp_servers,
        tools: want.tools,
      });
      log(`updated ${job.id} agent ${have.id} to version ${updated.version}`);
      agents[job.id] = have.id;
    } else {
      agents[job.id] = have.id;
    }
  }
  return { environmentID, agents };
}

export function agentChanged(agent: Agent, job: Job): boolean {
  const want = agentParams(job);
  const model = typeof want.model === "string" ? want.model : want.model.id;
  return agent.system !== want.system || agent.model.id !== model || agent.description !== want.description;
}

export interface StartTaf {
  name: string;
  job: string;
  /** The taf's own KIFF agent key: its Card. */
  kiffKey: string;
  cron?: string;
  timezone?: string;
  budgetCents?: number;
}

/**
 * Starts a taf: a vault holding its KIFF key, and a deployment that runs its
 * job's agent on a schedule with that vault. Runs once now as well.
 */
export async function startTaf(client: Anthropic, s: Setup, spec: StartTaf): Promise<{ deployment: Deployment; sessionID: string | null }> {
  const job = jobById(spec.job);
  const agentID = s.agents[job.id];
  if (!agentID) throw new Error(`no agent for ${job.id}; run setup first`);

  const vault = await client.beta.vaults.create({
    display_name: `tafs · ${spec.name}`,
    metadata: { [META.taf]: spec.name, [META.job]: job.id },
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
    }),
  );
  const run = await client.beta.deployments.run(deployment.id);
  return { deployment, sessionID: run.session_id ?? null };
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
