import Anthropic from "@anthropic-ai/sdk";
import { GetSecretValueCommand, SecretsManagerClient } from "@aws-sdk/client-secrets-manager";
import { setup, type Setup } from "../runtime.js";
import { handle, type HTTPEvent, type HTTPResult } from "./handler.js";

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

export async function handler(event: HTTPEvent): Promise<HTTPResult> {
  return handle(event, { anthropic, setup: ensureSetup });
}
