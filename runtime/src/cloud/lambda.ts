import Anthropic from "@anthropic-ai/sdk";
import { type AttributeValue, ConditionalCheckFailedException, DeleteItemCommand, DynamoDBClient, GetItemCommand, PutItemCommand, UpdateItemCommand } from "@aws-sdk/client-dynamodb";
import { GetSecretValueCommand, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";
import { setup, type Setup } from "../runtime.js";
import { apnsSender } from "./apns.js";
import { type Claims, handle, type HTTPEvent, type Removals, type HTTPResult, type Names, type Slots } from "./handler.js";
import type { Device, PushStore } from "./push.js";

// Kept for the life of the container: the key, the client, the agents.
let client: Promise<Anthropic> | undefined;
let ready: Promise<Setup> | undefined;

function anthropic(): Promise<Anthropic> {
  client ??= (async () => {
    const secretID = process.env.ANTHROPIC_SECRET_ID;
    if (!secretID) throw new Error("ANTHROPIC_SECRET_ID is not set");
    const out = await new SecretsManagerClient({}).send(new GetSecretValueCommand({ SecretId: secretID }));
    const apiKey = (out.SecretString ?? "").trim();
    if (!apiKey) throw new Error("the Anthropic key secret is empty");
    return new Anthropic({ apiKey });
  })();
  client.catch(() => { client = undefined; });
  return client;
}

function ensureSetup(): Promise<Setup> {
  ready ??= anthropic().then((c) => setup(c));
  ready.catch(() => { ready = undefined; });
  return ready;
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
 * Claimed drafts, in the same table: the vault holding each one's key, kept
 * 30 days so a failed start can resume; removed once its taf starts.
 */
const claims: Claims = {
  async get(tenant, draftID) {
    const out = await dynamo.send(new GetItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
      Key: { id: { S: `draft#${tenant}#${draftID}` } },
      ConsistentRead: true,
    }));
    const i = out.Item;
    if (!i?.secret?.S || !i.name?.S || !i.agent?.S || !i.vault?.S) return undefined;
    return { secret: i.secret.S, name: i.name.S, agentID: i.agent.S, vaultID: i.vault.S };
  },
  async put(tenant, draftID, c) {
    await dynamo.send(new PutItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
      Item: {
        id: { S: `draft#${tenant}#${draftID}` },
        secret: { S: c.secret }, name: { S: c.name }, agent: { S: c.agentID }, vault: { S: c.vaultID },
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

export async function handler(event: HTTPEvent): Promise<HTTPResult> {
  return handle(event, { anthropic, setup: ensureSetup, names, slots, claims, push, apns, removals });
}
