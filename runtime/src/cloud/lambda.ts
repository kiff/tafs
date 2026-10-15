import Anthropic from "@anthropic-ai/sdk";
import { ConditionalCheckFailedException, DeleteItemCommand, DynamoDBClient, GetItemCommand, PutItemCommand } from "@aws-sdk/client-dynamodb";
import { GetSecretValueCommand, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";
import { setup, type Setup } from "../runtime.js";
import { type Claims, handle, type HTTPEvent, type HTTPResult, type Names, type Slots } from "./handler.js";

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
  async claim(tenant, name) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
        Item: { id: { S: `${tenant}#${name}` }, at: { S: new Date().toISOString() } },
        ConditionExpression: "attribute_not_exists(id)",
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
  async release(tenant, name) {
    await dynamo.send(new DeleteItemCommand({
      TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
      Key: { id: { S: `${tenant}#${name}` } },
    }));
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
        ConditionExpression: "attribute_not_exists(id)",
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  },
};

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

export async function handler(event: HTTPEvent): Promise<HTTPResult> {
  return handle(event, { anthropic, setup: ensureSetup, names, slots, claims });
}
