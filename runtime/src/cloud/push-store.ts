import type { AttributeValue } from "@aws-sdk/client-dynamodb";
import { parseKinds } from "./alert-preferences.js";
import type { Device } from "./push.js";

/**
 * A phone as the push store keeps it (`push#<tenant>`, under `devices`, by its token): every field of a Device but the
 * token, which is the entry's key. A field left out here is lost at the next read, as `lang` once was, so every alert came
 * in English whatever the phone asked in: deviceOf(token, deviceItem(d)) gives back every field of d.
 */
export function deviceItem(d: Device): Record<string, AttributeValue> {
  return {
    platform: { S: d.platform ?? "ios" }, environment: { S: d.environment }, subject: { S: d.subject }, answers: { BOOL: d.answers },
    adds_tasks: { BOOL: d.addsTasks === true }, manages: { BOOL: d.manages === true }, silent: { BOOL: d.silent === true },
    ...(d.kinds ? { kinds: { S: JSON.stringify(d.kinds) } } : {}), ...(d.alerts === false ? { alerts: { BOOL: false } } : {}),
    ...(d.lang ? { lang: { S: d.lang } } : {}), at: { S: d.at },
  };
}

/** The phone the push store keeps under `token`; none for an entry with no environment it knows. */
export function deviceOf(token: string, m: Record<string, AttributeValue>): Device | undefined {
  const env = m.environment?.S;
  if (env !== "sandbox" && env !== "production") return undefined;
  return {
    token, platform: m.platform?.S === "android" ? "android" : m.platform?.S === "macos" ? "macos" : "ios", environment: env,
    subject: m.subject?.S ?? "", answers: m.answers?.BOOL === true, addsTasks: m.adds_tasks?.BOOL === true,
    ...(m.manages ? { manages: m.manages.BOOL === true } : {}), silent: m.silent?.BOOL === true,
    ...(m.kinds?.S ? { kinds: parseKinds(JSON.parse(m.kinds.S)) } : {}), ...(m.alerts?.BOOL === false ? { alerts: false as const } : {}),
    ...(m.lang?.S ? { lang: m.lang.S } : {}), at: m.at?.S ?? "",
  };
}
