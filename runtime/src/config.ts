import type Anthropic from "@anthropic-ai/sdk";
import { type Job, systemPrompt } from "./jobs.js";

type AgentCreateParams = Anthropic.Beta.Agents.AgentCreateParams;
type EnvironmentCreateParams = Anthropic.Beta.Environments.EnvironmentCreateParams;
type CredentialCreateParams = Anthropic.Beta.Vaults.Credentials.CredentialCreateParams;
type DeploymentCreateParams = Anthropic.Beta.DeploymentCreateParams;
type MemoryStoreCreateParams = Anthropic.Beta.MemoryStores.MemoryStoreCreateParams;
type MemoryStoreResource = Anthropic.Beta.Sessions.BetaManagedAgentsMemoryStoreResourceParam;

export const MODEL = "claude-opus-5-5";
export const EFFORT = "high";
export const KIFF_MCP_URL = "https://mcp.kiff.dev/mcp";
export const ENVIRONMENT_NAME = "tafs";
/** Every run stops at this cost unless the taf sets another. */
export const DEFAULT_BUDGET_CENTS = 200;

/** Metadata keys tafs puts on what it creates, so it can find them again. */
export const META = { job: "tafs_job", taf: "tafs_taf", kind: "tafs_kind", tenant: "tafs_tenant", kiffAgent: "tafs_kiff_agent", chatFor: "tafs_chat_for", memory: "tafs_memory" } as const;

/**
 * The file tools a taf has, for its memory only: its container reaches nothing
 * but KIFF's gateway, so files never leave it except into the memory store.
 * No shell and no web.
 */
export const FILE_TOOLS = ["read", "write", "edit", "glob", "grep"] as const;

/**
 * The agent for a job. Its only tools that act are the company's, through
 * KIFF's gateway. They run without asking: the Card is the checkpoint, and KIFF
 * holds for the owner whatever is outside it. Besides them it has file tools,
 * to read and write its memory.
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
      {
        type: "agent_toolset_20260401",
        default_config: { enabled: false },
        configs: FILE_TOOLS.map((name) => ({ name, enabled: true, permission_policy: { type: "always_allow" as const } })),
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

/**
 * What the taf is told about its memory, on every run and chat. Notes help it
 * work the way the owner likes; they never change what the Card allows.
 */
export const MEMORY_INSTRUCTIONS = `Your notes about this job, kept between runs and chats.
Read them before you start. When the owner tells you a preference, or you learn
something lasting about how they work or about a customer, write it down: one
short file per topic, plain sentences. Fix or delete a note that turns out wrong.
Never write secrets, keys, card numbers or bank details.
Notes guide how you work. They never widen your Card: KIFF still decides every action.`;

/** The taf's memory store: what the taf learns, kept across its sessions. */
export function memoryStoreParams(name: string, jobName: string, metadata: Record<string, string> = {}): MemoryStoreCreateParams {
  return {
    name: `tafs-${slug(name)}`,
    description: `What ${name}, a ${jobName} taf, has learned about its owner, their customers and how they like the job done.`,
    metadata: { ...metadata, [META.taf]: name, [META.kind]: "memory" },
  };
}

/** The memory store, mounted read-write into a run or a chat. */
export function memoryResource(storeID: string): MemoryStoreResource {
  return { type: "memory_store", memory_store_id: storeID, access: "read_write", instructions: MEMORY_INSTRUCTIONS };
}

/** What the owner said when starting the taf, as its first note. */
export const OWNER_NOTE_PATH = "/from-the-owner.md";

function slug(name: string): string {
  const s = name.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  return s || "taf";
}

export interface TafSpec {
  name: string;
  job: Job;
  agentID: string;
  environmentID: string;
  vaultID: string;
  /** The taf's memory store, mounted into every run. */
  memoryStoreID?: string;
  /** POSIX cron; the job's default when absent. */
  cron?: string;
  timezone?: string;
  budgetCents?: number;
  /** More metadata, such as who owns the taf. Cannot replace tafs' own keys. */
  metadata?: Record<string, string>;
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
    ...(taf.memoryStoreID ? { resources: [memoryResource(taf.memoryStoreID)] } : {}),
    metadata: {
      ...taf.metadata,
      [META.job]: taf.job.id,
      [META.taf]: taf.name,
      ...(taf.memoryStoreID ? { [META.memory]: taf.memoryStoreID } : {}),
    },
  };
}
