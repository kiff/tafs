import Anthropic from "@anthropic-ai/sdk";
import { ConditionalCheckFailedException, DeleteItemCommand, DynamoDBClient, PutItemCommand } from "@aws-sdk/client-dynamodb";
import { GetSecretValueCommand, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";
import { setup, type Setup } from "../runtime.js";
import { handle, type HTTPEvent, type HTTPResult, type Names } from "./handler.js";

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
  async claim(tenant, name, staleBefore) {
    try {
      await dynamo.send(new PutItemCommand({
        TableName: process.env.NAMES_TABLE ?? "tafs-cloud-names",
        Item: { id: { S: `${tenant}#${name}` }, at: { S: new Date().toISOString() } },
        // ISO times in UTC compare correctly as strings.
        ConditionExpression: "attribute_not_exists(id) OR #at < :stale",
        ExpressionAttributeNames: { "#at": "at" },
        ExpressionAttributeValues: { ":stale": { S: staleBefore.toISOString() } },
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

export async function handler(event: HTTPEvent): Promise<HTTPResult> {
  return handle(event, { anthropic, setup: ensureSetup, names });
}
