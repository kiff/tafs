import type Anthropic from "@anthropic-ai/sdk";
import { type Job, systemPrompt } from "./jobs.js";

type AgentCreateParams = Anthropic.Beta.Agents.AgentCreateParams;
type EnvironmentCreateParams = Anthropic.Beta.Environments.EnvironmentCreateParams;
type CredentialCreateParams = Anthropic.Beta.Vaults.Credentials.CredentialCreateParams;
type DeploymentCreateParams = Anthropic.Beta.DeploymentCreateParams;

export const MODEL = "claude-opus-5-5";
export const EFFORT = "high";
export const KIFF_MCP_URL = "https://mcp.kiff.dev/mcp";
export const ENVIRONMENT_NAME = "tafs";
/** Every run stops at this cost unless the taf sets another. */
export const DEFAULT_BUDGET_CENTS = 200;

/** Metadata keys tafs puts on what it creates, so it can find them again. */
export const META = { job: "tafs_job", taf: "tafs_taf", kind: "tafs_kind" } as const;

/**
 * The agent for a job. Its only tools are the company's, through KIFF's
 * gateway. They run without asking: the Card is the checkpoint, and KIFF holds
 * for the owner whatever is outside it.
 */
export function agentParams(job: Job): AgentCreateParams {
  return {
    name: `tafs · ${job.name}`,
    description: job.summary,
    model: { id: MODEL, effort: EFFORT },
    system: systemPrompt(job),
    mcp_servers: [{ type: "url", name: "kiff", url: KIFF_MCP_URL }],
    tools: [
      {
        type: "mcp_toolset",
        mcp_server_name: "kiff",
        default_config: { enabled: true, permission_policy: { type: "always_allow" } },
      },
    ],
    metadata: { [META.job]: job.id },
  };
}

/** Where runs happen: no packages, and the network only reaches KIFF's gateway. */
export function environmentParams(): EnvironmentCreateParams {
  return {
    name: ENVIRONMENT_NAME,
    config: {
      type: "cloud",
      networking: { type: "limited", allow_package_managers: false, allow_mcp_servers: true },
    },
  };
}

/** The taf's KIFF credential, kept in Anthropic's vault, never in the sandbox. */
export function credentialParams(kiffKey: string): CredentialCreateParams {
  if (!kiffKey.trim()) throw new Error("the taf needs its KIFF agent key");
  return {
    display_name: "KIFF agent key",
    auth: { type: "static_bearer", mcp_server_url: KIFF_MCP_URL, token: kiffKey.trim() },
  };
}

export interface TafSpec {
  name: string;
  job: Job;
  agentID: string;
  environmentID: string;
  vaultID: string;
  /** POSIX cron; the job's default when absent. */
  cron?: string;
  timezone?: string;
  budgetCents?: number;
}

export function deploymentParams(taf: TafSpec): DeploymentCreateParams {
  const cron = taf.cron ?? taf.job.cron;
  if (cron.trim().split(/\s+/).length !== 5) throw new Error(`"${cron}" is not a five-field cron expression`);
  const budget = taf.budgetCents ?? DEFAULT_BUDGET_CENTS;
  if (!Number.isInteger(budget) || budget <= 0) throw new Error("the budget is a whole number of cents above zero");
  return {
    name: taf.name,
    description: taf.job.summary,
    agent: taf.agentID,
    environment_id: taf.environmentID,
    vault_ids: [taf.vaultID],
    initial_events: [{ type: "user.message", content: [{ type: "text", text: taf.job.kickoff }] }],
    schedule: { type: "cron", expression: cron.trim(), timezone: taf.timezone ?? "UTC" },
    budget: { type: "limit", max_list_cost: { amount: String(budget), currency: "USD" } },
    metadata: { [META.job]: taf.job.id, [META.taf]: taf.name },
  };
}
