import { describe, expect, it } from "vitest";
import { appSecret } from "../src/cloud/appsecret.js";

/** A Secrets Manager stand-in: secrets by region and id, and every read it was asked for. */
function store(held: Record<string, string>) {
  const asked: string[] = [];
  const read = async (id: string, region: string) => {
    asked.push(`${region}:${id}`);
    return held[`${region}:${id}`];
  };
  return { read, asked };
}

const regions = { agents: "eu-west-1", data: "eu-west-1" };
const oldEnv = { APNS_SECRET_ID: "tafs/apns-key", FCM_SECRET_ID: "tafs/fcm", DOORS_SECRET_ID: "tafs/doors" };

describe("tafs cloud's one secret", () => {
  it("reads every part from TAFS_SECRET_ID, once, and never the old secrets", async () => {
    const s = store({ "eu-west-1:tafs": JSON.stringify({ apns: "-----BEGIN PRIVATE KEY-----", fcm: { client_email: "a@b", private_key: "k" }, doors: { email_api_key: "e" } }) });
    const keys = appSecret(s.read, { ...oldEnv, TAFS_SECRET_ID: "tafs" }, regions);
    expect(await keys.text("apns")).toBe("-----BEGIN PRIVATE KEY-----");
    expect(JSON.parse((await keys.text("fcm"))!)).toEqual({ client_email: "a@b", private_key: "k" });
    expect(JSON.parse((await keys.text("doors"))!)).toEqual({ email_api_key: "e" });
    expect(s.asked).toEqual(["eu-west-1:tafs"]);
  });

  it("falls back to a part's old secret while the one secret lacks it, so the move takes no key away", async () => {
    const s = store({ "eu-west-1:tafs": JSON.stringify({ apns: "pem" }), "eu-west-1:tafs/fcm": '{"client_email":"old"}', "eu-west-1:tafs/doors": "{}" });
    const keys = appSecret(s.read, { ...oldEnv, TAFS_SECRET_ID: "tafs" }, regions);
    expect(await keys.text("apns")).toBe("pem");
    expect(await keys.text("fcm")).toBe('{"client_email":"old"}');
    expect(s.asked).toContain("eu-west-1:tafs/fcm");
    expect(s.asked).not.toContain("eu-west-1:tafs/apns-key");
  });

  it("without TAFS_SECRET_ID, reads each old secret in its own region, as before", async () => {
    const s = store({ "us-east-1:tafs/apns-key": "pem" });
    const keys = appSecret(s.read, { APNS_SECRET_ID: "tafs/apns-key" }, { agents: "eu-west-1", data: "us-east-1" });
    expect(await keys.text("apns")).toBe("pem");
    expect(keys.configured("apns")).toBe(true);
    expect(keys.configured("fcm")).toBe(false);
    expect(await keys.text("fcm")).toBeUndefined();
  });

  it("a one secret that isn't JSON, or holds an empty part, falls back rather than handing out nothing", async () => {
    const s = store({ "eu-west-1:tafs": "not json", "eu-west-1:tafs/apns-key": "pem" });
    expect(await appSecret(s.read, { ...oldEnv, TAFS_SECRET_ID: "tafs" }, regions).text("apns")).toBe("pem");
    const t = store({ "eu-west-1:tafs": JSON.stringify({ apns: "" }), "eu-west-1:tafs/apns-key": "pem" });
    expect(await appSecret(t.read, { ...oldEnv, TAFS_SECRET_ID: "tafs" }, regions).text("apns")).toBe("pem");
  });

  it("TAFS_SECRET_REGION puts the one secret in another region", async () => {
    const s = store({ "us-east-1:tafs": JSON.stringify({ apns: "pem" }) });
    expect(await appSecret(s.read, { TAFS_SECRET_ID: "tafs", TAFS_SECRET_REGION: "us-east-1" }, regions).text("apns")).toBe("pem");
  });

  it("a failed read of the one secret isn't kept: that call falls back to the old secret, and the next reads again", async () => {
    const s = store({ "eu-west-1:tafs": JSON.stringify({ apns: "new pem" }), "eu-west-1:tafs/apns-key": "old pem" });
    let fail = 1;
    const read = async (id: string, region: string) => {
      if (id === "tafs" && fail-- > 0) {
        s.asked.push(`${region}:${id} (failed)`);
        throw new Error("ThrottlingException");
      }
      return s.read(id, region);
    };
    const keys = appSecret(read, { ...oldEnv, TAFS_SECRET_ID: "tafs" }, regions);
    expect(await keys.text("apns")).toBe("old pem");
    expect(await keys.text("apns")).toBe("new pem");
    expect(s.asked).toEqual(["eu-west-1:tafs (failed)", "eu-west-1:tafs/apns-key", "eu-west-1:tafs"]);
  });

  it("a failed read of the one secret, with no old secret to fall back on, is the caller's error and is retried", async () => {
    let fail = 1;
    const read = async (id: string) => {
      if (fail-- > 0) throw new Error("AccessDeniedException");
      return id === "tafs" ? JSON.stringify({ apns: "pem" }) : undefined;
    };
    const keys = appSecret(read, { TAFS_SECRET_ID: "tafs" }, regions);
    await expect(keys.text("apns")).rejects.toThrow("AccessDeniedException");
    expect(await keys.text("apns")).toBe("pem");
  });
});
