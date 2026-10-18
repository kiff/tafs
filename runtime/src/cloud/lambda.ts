import { type AttributeValue, ConditionalCheckFailedException, DeleteItemCommand, DynamoDBClient, GetItemCommand, PutItemCommand, ScanCommand, UpdateItemCommand } from "@aws-sdk/client-dynamodb";
import { CreateSecretCommand, DeleteSecretCommand, GetSecretValueCommand, PutSecretValueCommand, ResourceExistsException, ResourceNotFoundException, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";
import { type AccountStore, keepAccount } from "./accounts.js";
import { Agents, envCredentials } from "./agents.js";
import { apnsSender } from "./apns.js";
import { forwardAlert } from "./forward.js";
import { type Accounts, type Claims, type Deps, handle, handleStream, type HTTPEvent, type HTTPResult, type Keys, type Names, type Removals, type Slots, type Taf, type Tafs, type UpgradeOutcome, upgradeDefinitions, upgradeFailure } from "./handler.js";
import type { Device, PushStore } from "./push.js";

/**
 * kiff/agents, where every taf runs: its API (AWS_IAM function URL, signed
 * with this function's role), its alerts URL, and the region of both, whose
 * Secrets Manager holds the tafs' KIFF keys and the accounts' alert secrets.
 */
function need(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set`);
  return v;
}
const AGENTS_REGION = process.env.AGENTS_REGION ?? "eu-west-1";
/** Where kiff/agents reads each taf's key: <prefix><tenant>/<key_ref>, key_ref being kiff/<taf>. */
const KEYS_PREFIX = process.env.AGENTS_SECRETS_PREFIX ?? "agents/";
/** Where kiff/agents' alerts function reads each KIFF account's alert secret: <prefix><account>. */
const ACCOUNTS_PREFIX = process.env.KIFF_ACCOUNTS_PREFIX ?? "agents-accounts/";

let agentsClient: Agents | undefined;
function agents(): Agents {
  agentsClient ??= new Agents({ url: need("AGENTS_URL"), region: AGENTS_REGION, credentials: envCredentials });
  return agentsClient;
}

const secrets = new SecretsManagerClient({ region: AGENTS_REGION });

/** Makes the secret, or gives it a new value when it exists. */
async function putSecret(name: string, value: string, description: string): Promise<void> {
  try {
    await secrets.send(new CreateSecretCommand({ Name: name, SecretString: value, Description: description }));
  } catch (err) {
    if (!(err instanceof ResourceExistsException)) throw err;
    await secrets.send(new PutSecretValueCommand({ SecretId: name, SecretString: value }));
  }
}

const keys: Keys = {
  put: (tenant, tafID, key) => putSecret(`${KEYS_PREFIX}${tenant}/kiff/${tafID}`, key, "A taf's KIFF agent key, for kiff/agents"),
  async remove(tenant, tafID) {
    try {
      // For good, at once: a removed taf's key must not outlive it.
      await secrets.send(new DeleteSecretCommand({ SecretId: `${KEYS_PREFIX}${tenant}/kiff/${tafID}`, ForceDeleteWithoutRecovery: true }));
    } catch (err) {
      if (!(err instanceof ResourceNotFoundException)) throw err;
    }
  },
};

/** Each account's alert secret for kiff/agents, with the hash of what was written (account#id) beside it in the table. */
const accountStore: AccountStore = {
  async hash(account) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: `account#${account}` } }, ConsistentRead: true }));
    return out.Item?.hash?.S;
  },
  async swapHash(account, prev, next) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: TABLE,
        Item: { id: { S: `account#${account}` }, hash: { S: next } },
        ConditionExpression: prev === undefined ? "attribute_not_exists(#h)" : "#h = :prev",
        ExpressionAttributeNames: { "#h": "hash" },
        ...(prev === undefined ? {} : { ExpressionAttributeValues: { ":prev": { S: prev } } }),
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
  writeSecret: (account, tenant, secret) =>
    putSecret(`${ACCOUNTS_PREFIX}${account}`, JSON.stringify({ tenant, secret }), "A KIFF account's alert secret, for kiff/agents' alerts"),
};
const accounts: Accounts = { put: (account, tenant, secret) => keepAccount(accountStore, account, tenant, secret) };

/** KIFF's alert, handed to kiff/agents' alerts function (forward.ts). */
function forward(signature: string, raw: string): Promise<void> {
  return forwardAlert(fetch, need("AGENTS_ALERTS_URL"), signature, raw);
}

/** Taf names per account, claimed with a conditional write so only one request wins. */
const dynamo = new DynamoDBClient({});
const names: Names = {
  async claim(tenant, name, owner) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
        Item: { id: { S: `${tenant}#${name}` }, owner: { S: owner }, at: { S: new Date().toISOString() } },
        ConditionExpression: "attribute_not_exists(id)",
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
  async release(tenant, name, owner) {
    try {
      await dynamo.send(new DeleteItemCommand({
        TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
        Key: { id: { S: `${tenant}#${name}` } },
        // Only this owner's claim, or one from before owners: never a newer start's.
        ConditionExpression: owner ? "attribute_not_exists(#o) OR #o = :o" : "attribute_not_exists(#o)",
        ExpressionAttributeNames: { "#o": "owner" },
        ...(owner ? { ExpressionAttributeValues: { ":o": { S: owner } } } : {}),
      }));
    } catch (err) {
      if (!(err instanceof ConditionalCheckFailedException)) throw err;
    }
  },
};

/**
 * Chat message slots, in the same table: one item per slot, claimed with
 * attribute_not_exists, and a ttl so DynamoDB removes old windows.
 */
const slots: Slots = {
  async claim(key, expiresAt) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
        Item: { id: { S: key }, ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } },
        // DynamoDB removes expired items late, so an expired claim is free too.
        ConditionExpression: "attribute_not_exists(id) OR #ttl < :now",
        ExpressionAttributeNames: { "#ttl": "ttl" },
        ExpressionAttributeValues: { ":now": { N: String(Math.floor(Date.now() / 1000)) } },
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
  async keep(key, expiresAt) {
    await dynamo.send(new PutItemCommand({
      TableName: TABLE,
      Item: { id: { S: key }, ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } },
    }));
  },
  async release(key) {
    await dynamo.send(new DeleteItemCommand({ TableName: TABLE, Key: { id: { S: key } } }));
  },
  async held(key) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: key } }, ConsistentRead: true }));
    const ttl = Number(out.Item?.ttl?.N ?? 0);
    return Boolean(out.Item) && ttl * 1000 > Date.now();
  },
};

const TABLE = process.env.NAMES_TABLE ?? "tafs-cloud-names";

/**
 * Each account's phones and KIFF target secrets, in one item of the same
 * table (push#tenant): devices and secrets are maps, each entry set or
 * removed on its own, so two phones registering at once both land.
 */
const MAX_SECRETS = 5;
const push: PushStore = {
  async get(tenant) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: `push#${tenant}` } }, ConsistentRead: true }));
    const devices: Device[] = Object.entries(out.Item?.devices?.M ?? {}).flatMap(([token, v]) => {
      const m = v.M ?? {};
      const env = m.environment?.S;
      return env === "sandbox" || env === "production"
        ? [{ token, environment: env, subject: m.subject?.S ?? "", answers: m.answers?.BOOL === true, at: m.at?.S ?? "" }]
        : [];
    });
    const secrets = Object.values(out.Item?.secrets?.M ?? {})
      .map((v) => ({ s: v.M?.secret?.S ?? "", at: v.M?.at?.S ?? "" }))
      .filter((x) => x.s)
      .sort((a, b) => b.at.localeCompare(a.at))
      .map((x) => x.s);
    return { devices, secrets };
  },
  async addDevice(tenant, d) {
    await setEntry(tenant, "devices", d.token, { environment: { S: d.environment }, subject: { S: d.subject }, answers: { BOOL: d.answers }, at: { S: d.at } });
  },
  async removeDevice(tenant, token) {
    await dynamo.send(new UpdateItemCommand({
      TableName: TABLE, Key: { id: { S: `push#${tenant}` } },
      UpdateExpression: "REMOVE devices.#k", ExpressionAttributeNames: { "#k": token },
    }));
  },
  async addSecret(tenant, targetID, secret, at) {
    await setEntry(tenant, "secrets", targetID, { secret: { S: secret }, at: { S: at } });
    // Keep the newest few: older targets were replaced in KIFF and sign nothing more.
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: `push#${tenant}` } }, ConsistentRead: true }));
    const old = Object.entries(out.Item?.secrets?.M ?? {})
      .sort(([, a], [, b]) => (b.M?.at?.S ?? "").localeCompare(a.M?.at?.S ?? ""))
      .slice(MAX_SECRETS)
      .map(([id]) => id);
    if (old.length === 0) return;
    await dynamo.send(new UpdateItemCommand({
      TableName: TABLE, Key: { id: { S: `push#${tenant}` } },
      UpdateExpression: `REMOVE ${old.map((_, i) => `secrets.#o${i}`).join(", ")}`,
      ExpressionAttributeNames: Object.fromEntries(old.map((id, i) => [`#o${i}`, id])),
    }));
  },
};

/** Sets map.key on the account's item, making the item and the map first if need be. */
async function setEntry(tenant: string, map: "devices" | "secrets", key: string, value: Record<string, AttributeValue>) {
  const Key = { id: { S: `push#${tenant}` } };
  await dynamo.send(new UpdateItemCommand({
    TableName: TABLE, Key,
    UpdateExpression: "SET #m = if_not_exists(#m, :empty)",
    ExpressionAttributeNames: { "#m": map }, ExpressionAttributeValues: { ":empty": { M: {} } },
  }));
  await dynamo.send(new UpdateItemCommand({
    TableName: TABLE, Key,
    UpdateExpression: "SET #m.#k = :v",
    ExpressionAttributeNames: { "#m": map, "#k": key }, ExpressionAttributeValues: { ":v": { M: value } },
  }));
}

/** Apple push with the "tafs push" key, read from Secrets Manager on first use. */
const apns = apnsSender({
  keyPEM: async () => {
    const id = process.env.APNS_SECRET_ID;
    if (!id) throw new Error("APNS_SECRET_ID is not set");
    const out = await new SecretsManagerClient({}).send(new GetSecretValueCommand({ SecretId: id }));
    return (out.SecretString ?? "").trim();
  },
  keyID: process.env.APNS_KEY_ID ?? "",
  teamID: process.env.APNS_TEAM_ID ?? "",
  topic: process.env.APNS_TOPIC ?? "bot.tafs.app",
});

/**
 * Each account's tafs, in one item (tafs#tenant): a map of taf id → the taf
 * as JSON, each entry set or removed on its own.
 */
const tafs: Tafs = {
  async list(tenant) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: `tafs#${tenant}` } }, ConsistentRead: true }));
    return Object.values(out.Item?.tafs?.M ?? {}).flatMap((v) => {
      try { return [JSON.parse(v.S ?? "") as Taf]; } catch { return []; }
    });
  },
  async get(tenant, id) {
    return (await this.list(tenant)).find((t) => t.id === id);
  },
  async put(tenant, taf) {
    const Key = { id: { S: `tafs#${tenant}` } };
    await dynamo.send(new UpdateItemCommand({ TableName: TABLE, Key, UpdateExpression: "SET tafs = if_not_exists(tafs, :empty)", ExpressionAttributeValues: { ":empty": { M: {} } } }));
    await dynamo.send(new UpdateItemCommand({ TableName: TABLE, Key, UpdateExpression: "SET tafs.#id = :taf", ExpressionAttributeNames: { "#id": taf.id }, ExpressionAttributeValues: { ":taf": { S: JSON.stringify(taf) } } }));
  },
  async remove(tenant, id) {
    await dynamo.send(new UpdateItemCommand({ TableName: TABLE, Key: { id: { S: `tafs#${tenant}` } }, UpdateExpression: "REMOVE tafs.#id", ExpressionAttributeNames: { "#id": id } }));
  },
  /** A scan of the table's tafs#… items, keys only: an upgrade's, by hand, not a request's. */
  async tenants() {
    const out: string[] = [];
    let start: Record<string, AttributeValue> | undefined;
    do {
      const page = await dynamo.send(new ScanCommand({
        TableName: TABLE, ProjectionExpression: "id", FilterExpression: "begins_with(id, :p)",
        ExpressionAttributeValues: { ":p": { S: "tafs#" } }, ExclusiveStartKey: start,
      }));
      for (const item of page.Items ?? []) if (item.id?.S) out.push(item.id.S.slice("tafs#".length));
      start = page.LastEvaluatedKey;
    } while (start);
    return out;
  },
};

/**
 * Claimed drafts, in the same table: the taf whose key each one's is (never
 * the key), kept 30 days so a failed start can resume; removed once its taf starts.
 */
const claims: Claims = {
  async get(tenant, draftID) {
    const out = await dynamo.send(new GetItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
      Key: { id: { S: `draft#${tenant}#${draftID}` } },
      ConsistentRead: true,
    }));
    const i = out.Item;
    if (!i?.secret?.S || !i.name?.S || !i.agent?.S || !i.taf?.S) return undefined;
    return { secret: i.secret.S, name: i.name.S, agentID: i.agent.S, tafID: i.taf.S };
  },
  async put(tenant, draftID, c) {
    await dynamo.send(new PutItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
      Item: {
        id: { S: `draft#${tenant}#${draftID}` },
        secret: { S: c.secret }, name: { S: c.name }, agent: { S: c.agentID }, taf: { S: c.tafID },
        ttl: { N: String(Math.floor(Date.now() / 1000) + 30 * 24 * 3600) },
      },
    }));
  },
  async remove(tenant, draftID) {
    await dynamo.send(new DeleteItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
      Key: { id: { S: `draft#${tenant}#${draftID}` } },
    }));
  },
};

/**
 * Removals not finished, per account, in one item (removing#tenant): a map of
 * deployment id → taf name, each entry set or removed on its own.
 */
const removals: Removals = {
  async list(tenant) {
    const out = await dynamo.send(new GetItemCommand({ TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names", Key: { id: { S: `removing#${tenant}` } }, ConsistentRead: true }));
    return Object.entries(out.Item?.tafs?.M ?? {}).map(([id, v]) => ({ id, name: v.S ?? "" }));
  },
  async add(tenant, r) {
    const TableName = process.env.NAMES_TABLE ?? "tafs-cloud-names";
    const Key = { id: { S: `removing#${tenant}` } };
    await dynamo.send(new UpdateItemCommand({ TableName, Key, UpdateExpression: "SET tafs = if_not_exists(tafs, :empty)", ExpressionAttributeValues: { ":empty": { M: {} } } }));
    await dynamo.send(new UpdateItemCommand({ TableName, Key, UpdateExpression: "SET tafs.#id = :name", ExpressionAttributeNames: { "#id": r.id }, ExpressionAttributeValues: { ":name": { S: r.name } } }));
  },
  async done(tenant, id) {
    await dynamo.send(new UpdateItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names", Key: { id: { S: `removing#${tenant}` } },
      UpdateExpression: "REMOVE tafs.#id", ExpressionAttributeNames: { "#id": id },
    }));
  },
};

function deps(): Deps {
  return { agents: agents(), keys, accounts, tafs, forward, names, slots, claims, push, apns, removals };
}

/**
 * An upgrade (kiff/tafs#69), invoked directly by the upgrade workflow's role,
 * never through the function's URL: a URL request always arrives as an HTTP
 * event, with requestContext, so it can't take this shape.
 */
interface UpgradeEvent {
  upgrade: { dry_run?: boolean };
}

function isUpgrade(event: unknown): event is UpgradeEvent {
  return typeof event === "object" && event !== null && !("requestContext" in event) && "upgrade" in event;
}

export async function handler(event: HTTPEvent | UpgradeEvent): Promise<HTTPResult | { upgraded: UpgradeOutcome[] }> {
  if (isUpgrade(event)) {
    try {
      return { upgraded: await upgradeDefinitions(deps(), { dryRun: event.upgrade.dry_run !== false }) };
    } catch (err) {
      // Listing the accounts failed. The caller's log is public: it gets the kind, and CloudWatch the rest.
      console.error("upgrade:", err);
      throw new Error(`the upgrade couldn't list the tafs: ${upgradeFailure(err)}`);
    }
  }
  return handle(event, deps());
}

/**
 * The runtime's streaming API (Node.js Lambda response streaming), on the
 * function's global: only inside Lambda, and only for a function URL in
 * RESPONSE_STREAM mode.
 */
interface ResponseStream {
  write(chunk: string): boolean;
  end(): void;
}
declare const awslambda: {
  streamifyResponse(h: (event: HTTPEvent, stream: ResponseStream) => Promise<void>): unknown;
  HttpResponseStream: { from(stream: ResponseStream, meta: { statusCode: number; headers: Record<string, string> }): ResponseStream };
};

async function streamed(event: HTTPEvent, out: ResponseStream): Promise<void> {
  const r = await handleStream(event, deps());
  const body = awslambda.HttpResponseStream.from(out, { statusCode: r.statusCode, headers: r.headers });
  try {
    for await (const chunk of r.body) body.write(chunk);
  } finally {
    body.end();
  }
}

/**
 * tafs-cloud-stream's handler (kiff/tafs#60): the same code as `handler`,
 * deployed to a second function whose URL streams. It serves one route,
 * POST /v1/tafs/{id}/messages/stream.
 */
export const stream = typeof awslambda === "undefined" ? streamed : awslambda.streamifyResponse(streamed);
