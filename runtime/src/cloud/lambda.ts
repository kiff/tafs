import { type AttributeValue, ConditionalCheckFailedException, DeleteItemCommand, DynamoDBClient, GetItemCommand, PutItemCommand, ScanCommand, UpdateItemCommand } from "@aws-sdk/client-dynamodb";
import { CreateSecretCommand, DeleteSecretCommand, GetSecretValueCommand, PutSecretValueCommand, ResourceExistsException, ResourceNotFoundException, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";
import { accountItems, accountPrefixes, isAccountItem } from "./account.js";
import { type AccountStore, keepAccount } from "./accounts.js";
import { Agents, envCredentials } from "./agents.js";
import { apnsSender } from "./apns.js";
import { type Deletion, type Deletions, httpPeer } from "./deletion.js";
import { fcmSender } from "./fcm.js";
import { KIFF_API } from "./kiff.js";
import { PLANQUE } from "./setupdef.js";
import { forwardAlert, type ReadyRun } from "./forward.js";
import { type AccountData, type Accounts, type Archived, type Claims, type Deps, handle, handleStream, sweepDeletions, sweepHandOvers, type HTTPEvent, type HTTPResult, type Keys, type Names, type Removals, type Slots, type Taf, type Tafs, type UpgradeOutcome, upgradeDefinitions, upgradeFailure } from "./handler.js";
import { routing, silencing, type Device, type PushStore } from "./push.js";
import type { CoachStore } from "./coach.js";
import type { DoorsConfig, DoorsDeps } from "./doors/doors.js";
import { emailProvider } from "./doors/email.js";
import type { DoorSecrets, DoorStore } from "./doors/model.js";
import { whatsappClient } from "./doors/whatsapp.js";
import { eventLog } from "./events.js";

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
function forward(signature: string, raw: string): Promise<ReadyRun | undefined> {
  return forwardAlert(fetch, need("AGENTS_ALERTS_URL"), signature, raw);
}

/**
 * Where tafs cloud keeps its data: its table and its push key. DATA_REGION
 * when set (eu-west-1), else this function's own region. It lets the data
 * move to the EU before the functions do, so the URLs the app and the email
 * door already call keep working meanwhile.
 */
const DATA_REGION = process.env.DATA_REGION || undefined;

/** Taf names per account, claimed with a conditional write so only one request wins. */
const dynamo = new DynamoDBClient({ region: DATA_REGION });
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
        ? [{ token, platform: m.platform?.S === "android" ? "android" as const : "ios" as const, environment: env, subject: m.subject?.S ?? "", answers: m.answers?.BOOL === true, addsTasks: m.adds_tasks?.BOOL === true, manages: m.manages?.BOOL === true, silent: m.silent?.BOOL === true, at: m.at?.S ?? "" }]
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
    await setEntry(tenant, "devices", d.token, { platform: { S: d.platform ?? "ios" }, environment: { S: d.environment }, subject: { S: d.subject }, answers: { BOOL: d.answers }, adds_tasks: { BOOL: d.addsTasks === true }, manages: { BOOL: d.manages === true }, silent: { BOOL: d.silent === true }, at: { S: d.at } });
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

/**
 * Google push for Android phones, with the Firebase project's service-account key, read from Secrets Manager on first
 * use. Off unless FCM_PROJECT_ID and FCM_SECRET_ID are set: an Android phone's registration is then refused.
 */
const fcm = process.env.FCM_PROJECT_ID && process.env.FCM_SECRET_ID
  ? fcmSender({
    projectId: process.env.FCM_PROJECT_ID,
    credentials: async () => {
      const out = await new SecretsManagerClient({ region: DATA_REGION }).send(new GetSecretValueCommand({ SecretId: process.env.FCM_SECRET_ID }));
      return JSON.parse(out.SecretString ?? "") as { client_email: string; private_key: string; token_uri?: string };
    },
  })
  : undefined;

/**
 * Push to every phone: Apple's with the "tafs push" key, read from Secrets Manager on first use, for an iPhone; Google's
 * for an Android phone. Quiet on the phones whose Sound for alerts is off.
 */
const apns = silencing(routing({
  ios: apnsSender({
    keyPEM: async () => {
      const id = process.env.APNS_SECRET_ID;
      if (!id) throw new Error("APNS_SECRET_ID is not set");
      const out = await new SecretsManagerClient({ region: DATA_REGION }).send(new GetSecretValueCommand({ SecretId: id }));
      return (out.SecretString ?? "").trim();
    },
    keyID: process.env.APNS_KEY_ID ?? "",
    teamID: process.env.APNS_TEAM_ID ?? "",
    topic: process.env.APNS_TOPIC ?? "bot.tafs.app",
  }),
  android: fcm,
}));

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
  async putIf(tenant, taf, read) {
    try {
      await dynamo.send(new UpdateItemCommand({
        TableName: TABLE, Key: { id: { S: `tafs#${tenant}` } },
        UpdateExpression: "SET tafs.#id = :taf", ConditionExpression: "tafs.#id = :read",
        ExpressionAttributeNames: { "#id": taf.id },
        ExpressionAttributeValues: { ":taf": { S: JSON.stringify(taf) }, ":read": { S: JSON.stringify(read) } },
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
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

/**
 * The chats each taf's owner archived while tafs cloud kept archives (kiff/tafs#82),
 * in one item per taf (archived#tenant#taf): a string set of sessions, to move to
 * kiff/agents. Its ttl takes it past when kiff/agents deletes the chats.
 */
const archived: Archived = {
  async list(tenant, tafID) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: `archived#${tenant}#${tafID}` } }, ConsistentRead: true }));
    return out.Item?.chats?.SS ?? [];
  },
  async remove(tenant, tafID) {
    await dynamo.send(new DeleteItemCommand({ TableName: TABLE, Key: { id: { S: `archived#${tenant}#${tafID}` } } }));
  },
};

/**
 * What tafs cloud keeps for an account beyond its tafs, erased when its owner
 * deletes it (kiff/tafs#185): every table item whose id names the account
 * (account.ts), found by a scan, and the account's alert secret for
 * kiff/agents. The tafs' keys go with each taf's removal, before this.
 */
const accountData: AccountData = {
  async erase(tenant) {
    const ids = new Set(accountItems(tenant));
    const prefixes = accountPrefixes(tenant);
    let start: Record<string, AttributeValue> | undefined;
    do {
      const page = await dynamo.send(new ScanCommand({
        TableName: TABLE, ProjectionExpression: "id", ExclusiveStartKey: start,
        FilterExpression: prefixes.map((_, i) => `begins_with(id, :p${i})`).join(" OR "),
        ExpressionAttributeValues: Object.fromEntries(prefixes.map((p, i) => [`:p${i}`, { S: p }])),
      }));
      for (const item of page.Items ?? []) if (item.id?.S && isAccountItem(item.id.S, tenant)) ids.add(item.id.S);
      start = page.LastEvaluatedKey;
    } while (start);
    for (const id of ids) await dynamo.send(new DeleteItemCommand({ TableName: TABLE, Key: { id: { S: id } } }));
    try {
      await secrets.send(new DeleteSecretCommand({ SecretId: `${ACCOUNTS_PREFIX}${tenant}`, ForceDeleteWithoutRecovery: true }));
    } catch (err) {
      if (!(err instanceof ResourceNotFoundException)) throw err;
    }
  },
};

/**
 * What Coach's tools keep (kiff/tafs#206), in the same table, each item under
 * its account's prefix and with a ttl: used permits, operations' answers,
 * tasks handed over, and the lines Coach hasn't read.
 */
const coach: CoachStore = {
  async once(key, expiresAt) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: TABLE,
        Item: { id: { S: key }, ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } },
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
  async get(key) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: key } }, ConsistentRead: true }));
    if (!out.Item || Number(out.Item.ttl?.N ?? 0) * 1000 <= Date.now()) return undefined;
    if (out.Item.doc?.S !== undefined) return JSON.parse(out.Item.doc.S);
    if (out.Item.list?.L) return out.Item.list.L.map((v) => JSON.parse(v.S ?? "null"));
    return undefined;
  },
  async put(key, value, expiresAt) {
    await dynamo.send(new PutItemCommand({
      TableName: TABLE,
      Item: { id: { S: key }, doc: { S: JSON.stringify(value) }, ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } },
    }));
  },
  async append(key, item, expiresAt) {
    await dynamo.send(new UpdateItemCommand({
      TableName: TABLE,
      Key: { id: { S: key } },
      UpdateExpression: "SET #l = list_append(if_not_exists(#l, :empty), :item), #ttl = :ttl",
      ExpressionAttributeNames: { "#l": "list", "#ttl": "ttl" },
      ExpressionAttributeValues: {
        ":empty": { L: [] }, ":item": { L: [{ S: JSON.stringify(item) }] },
        ":ttl": { N: String(Math.floor(expiresAt.getTime() / 1000)) },
      },
    }));
  },
  async take(key) {
    const out = await dynamo.send(new DeleteItemCommand({ TableName: TABLE, Key: { id: { S: key } }, ReturnValues: "ALL_OLD" }));
    if (!out.Attributes || Number(out.Attributes.ttl?.N ?? 0) * 1000 <= Date.now()) return [];
    return (out.Attributes.list?.L ?? []).map((v) => JSON.parse(v.S ?? "null"));
  },
  async remove(key) {
    await dynamo.send(new DeleteItemCommand({ TableName: TABLE, Key: { id: { S: key } } }));
  },
};

/**
 * What doors keep (doors/model.ts), in the same table: each item a JSON doc
 * with an optional ttl, and a day's count as a number (`n`) that only grows
 * to its limit.
 */
const doorStore: DoorStore = {
  async get(key) {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: key } }, ConsistentRead: true }));
    const ttl = out.Item?.ttl?.N;
    if (!out.Item || (ttl && Number(ttl) * 1000 <= Date.now())) return undefined;
    if (out.Item.n?.N !== undefined) return Number(out.Item.n.N);
    return out.Item.doc?.S !== undefined ? JSON.parse(out.Item.doc.S) : undefined;
  },
  async put(key, value, expiresAt) {
    await dynamo.send(new PutItemCommand({
      TableName: TABLE,
      Item: { id: { S: key }, doc: { S: JSON.stringify(value) }, ...(expiresAt ? { ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } } : {}) },
    }));
  },
  async create(key, value, expiresAt) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: TABLE,
        Item: { id: { S: key }, doc: { S: JSON.stringify(value) }, ...(expiresAt ? { ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } } : {}) },
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
  async remove(key) {
    await dynamo.send(new DeleteItemCommand({ TableName: TABLE, Key: { id: { S: key } } }));
  },
  async swap(key, prev, next, expiresAt) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: TABLE,
        Item: { id: { S: key }, doc: { S: JSON.stringify(next) }, ...(expiresAt ? { ttl: { N: String(Math.floor(expiresAt.getTime() / 1000)) } } : {}) },
        // What get read: the same JSON, written by JSON.stringify, read back by JSON.parse.
        ...(prev === undefined
          ? { ConditionExpression: "attribute_not_exists(id) OR #ttl < :now", ExpressionAttributeNames: { "#ttl": "ttl" }, ExpressionAttributeValues: { ":now": { N: String(Math.floor(Date.now() / 1000)) } } }
          : { ConditionExpression: "#doc = :prev", ExpressionAttributeNames: { "#doc": "doc" }, ExpressionAttributeValues: { ":prev": { S: JSON.stringify(prev) } } }),
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
  async bump(key, max, expiresAt) {
    try {
      await dynamo.send(new UpdateItemCommand({
        TableName: TABLE, Key: { id: { S: key } },
        UpdateExpression: "ADD n :one SET #ttl = :ttl",
        ConditionExpression: "attribute_not_exists(n) OR n < :max",
        ExpressionAttributeNames: { "#ttl": "ttl" },
        ExpressionAttributeValues: { ":one": { N: "1" }, ":max": { N: String(max) }, ":ttl": { N: String(Math.floor(expiresAt.getTime() / 1000)) } },
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
};

/** A door's secrets (a WhatsApp number's business token), beside the tafs' keys. */
const doorSecrets: DoorSecrets = {
  async get(name) {
    try {
      return (await secrets.send(new GetSecretValueCommand({ SecretId: name }))).SecretString;
    } catch (err) {
      if (err instanceof ResourceNotFoundException) return undefined;
      throw err;
    }
  },
  put: (name, value) => putSecret(name, value, "A taf door's token"),
  async remove(name) {
    try {
      await secrets.send(new DeleteSecretCommand({ SecretId: name, ForceDeleteWithoutRecovery: true }));
    } catch (err) {
      if (!(err instanceof ResourceNotFoundException)) throw err;
    }
  },
};

/**
 * Doors' setup, read once per container: the deployment's URL and email
 * domain from the environment, and the providers' keys from one secret
 * (DOORS_SECRET_ID, JSON). A provider whose keys aren't there isn't offered.
 */
let doorsConfig: Promise<DoorsConfig> | undefined;
function readDoorsConfig(): Promise<DoorsConfig> {
  doorsConfig ??= (async () => {
    const id = process.env.DOORS_SECRET_ID;
    const raw = id ? (await secrets.send(new GetSecretValueCommand({ SecretId: id }))).SecretString ?? "{}" : "{}";
    const k = JSON.parse(raw) as Record<string, unknown>;
    const str = (name: string) => (typeof k[name] === "string" ? (k[name] as string) : "");
    const cfg: DoorsConfig = {
      publicURL: (process.env.DOORS_PUBLIC_URL ?? "").replace(/\/+$/, ""),
      secretsPrefix: process.env.DOORS_SECRETS_PREFIX ?? "tafs-doors/",
    };
    if (process.env.DOORS_EMAIL_DOMAIN && str("email_api_key") && str("email_webhook_secret")) {
      cfg.emailDomain = process.env.DOORS_EMAIL_DOMAIN;
      cfg.email = emailProvider(str("email_api_key"));
      cfg.emailWebhookSecret = str("email_webhook_secret");
    }
    if (str("whatsapp_app_id") && str("whatsapp_app_secret") && str("whatsapp_config_id") && str("whatsapp_verify_token")) {
      cfg.whatsapp = {
        client: whatsappClient({ id: str("whatsapp_app_id"), secret: str("whatsapp_app_secret") }),
        appID: str("whatsapp_app_id"), appSecret: str("whatsapp_app_secret"), configID: str("whatsapp_config_id"),
        verifyToken: str("whatsapp_verify_token"), ...(process.env.DOORS_WHATSAPP_REGION ? { region: process.env.DOORS_WHATSAPP_REGION } : {}),
      };
    }
    return cfg;
  })().catch((err) => {
    doorsConfig = undefined;
    throw err;
  });
  return doorsConfig;
}

/** Halts, resumes and door events, in the same table as the doors (kiff/tafs#236). */
const events = eventLog(doorStore);

/** Doors run only where the deployment says where it is: its runs must be able to announce they ended. */
const doors: DoorsDeps | undefined = process.env.DOORS_PUBLIC_URL ? { store: doorStore, secrets: doorSecrets, config: readDoorsConfig } : undefined;

/** tafs cloud's own address behind CloudFront (TAFS_PUBLIC_URL); unset, each request's host. */
const publicURL = (process.env.TAFS_PUBLIC_URL ?? "").replace(/\/+$/, "");

/**
 * Deletions driven by tafs cloud, kept in one item, `deletions`, by account, until each is finished:
 * outside every account's own items, so erasing the account keeps it. A finished one leaves only
 * that it was finished, and when, in the log.
 */
const deletions: Deletions = {
  async get(tenant) {
    return (await this.pending()).find((d) => d.tenant === tenant);
  },
  async put(d) {
    await dynamo.send(new UpdateItemCommand({
      TableName: TABLE, Key: { id: { S: "deletions" } },
      UpdateExpression: "SET #m = if_not_exists(#m, :empty)",
      ExpressionAttributeNames: { "#m": "pending" }, ExpressionAttributeValues: { ":empty": { M: {} } },
    }));
    await dynamo.send(new UpdateItemCommand({
      TableName: TABLE, Key: { id: { S: "deletions" } },
      UpdateExpression: "SET #m.#k = :v",
      ExpressionAttributeNames: { "#m": "pending", "#k": d.tenant }, ExpressionAttributeValues: { ":v": { S: JSON.stringify(d) } },
    }));
  },
  async pending() {
    const out = await dynamo.send(new GetItemCommand({ TableName: TABLE, Key: { id: { S: "deletions" } }, ConsistentRead: true }));
    return Object.values(out.Item?.pending?.M ?? {}).flatMap((v) => {
      try { return [JSON.parse(v.S ?? "") as Deletion]; } catch { return []; }
    });
  },
  async finished(tenant, at, outcome) {
    await dynamo.send(new UpdateItemCommand({
      TableName: TABLE, Key: { id: { S: "deletions" } },
      UpdateExpression: "REMOVE #m.#k", ExpressionAttributeNames: { "#m": "pending", "#k": tenant },
    }));
    // A dropped deletion deleted nothing: the log never says otherwise.
    console.log(outcome === "deleted" ? `account deleted: ${tenant} at ${at}` : `account deletion dropped, nothing deleted: ${tenant} at ${at}`);
  },
};

/** planque and KIFF, for a deletion: each checks the phone's signed intent itself. */
const deletionPeers = {
  planque: httpPeer(process.env.PLANQUE_URL ?? PLANQUE, "/v1/account-deletions"),
  kiff: httpPeer(process.env.KIFF_API_URL ?? KIFF_API, "/v1/account-deletions"),
};

function deps(): Deps {
  return { agents: agents(), keys, accounts, tafs, forward, names, slots, claims, push, apns, androidPush: Boolean(fcm), removals, archived, accountData, deletions, deletionPeers, coach, events, ...(doors ? { doors } : {}), ...(publicURL ? { publicURL } : {}) };
}

/**
 * An upgrade (kiff/tafs#69), invoked directly by the upgrade workflow's role,
 * never through the function's URL: a URL request always arrives as an HTTP
 * event, with requestContext, so it can't take this shape.
 */
interface UpgradeEvent {
  upgrade: { dry_run?: boolean };
}

/** The deletion sweep, invoked by its schedule: every deletion not finished, and every hand-over a server error left undone, tried again. */
interface SweepEvent {
  sweep: "deletions";
}

function isSweep(event: unknown): event is SweepEvent {
  return typeof event === "object" && event !== null && !("requestContext" in event) && (event as { sweep?: unknown }).sweep === "deletions";
}

function isUpgrade(event: unknown): event is UpgradeEvent {
  return typeof event === "object" && event !== null && !("requestContext" in event) && "upgrade" in event;
}

export async function handler(event: HTTPEvent | UpgradeEvent | SweepEvent): Promise<HTTPResult | { upgraded: UpgradeOutcome[] } | { swept: unknown[]; handovers?: unknown[] }> {
  if (isSweep(event)) {
    // The same schedule retries Coach's hand-overs a server error left undone.
    const d = deps();
    return { swept: await sweepDeletions(d), handovers: await sweepHandOvers(d) };
  }
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
 * deployed to a second function whose URL streams. It serves two routes,
 * POST /v1/tafs/{id}/messages/stream and GET /v1/tafs/{id}/runs/{run}/stream
 * (kiff/tafs#285).
 */
export const stream = typeof awslambda === "undefined" ? streamed : awslambda.streamifyResponse(streamed);
