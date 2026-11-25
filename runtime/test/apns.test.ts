import { execFile } from "node:child_process";
import { generateKeyPairSync } from "node:crypto";
import { mkdtempSync, writeFileSync } from "node:fs";
import http2 from "node:http2";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { build } from "esbuild";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { apnsSender, type ProviderToken, TOKEN_MINUTES, type TokenStore } from "../src/cloud/apns.js";

const run = promisify(execFile);

// Apple, slow to answer: the push is in flight while nothing else is.
let server: http2.Http2Server;
let host = "";
/** Each push's `apns-topic`, as Apple got it. */
const topics: string[] = [];
beforeAll(async () => {
  server = http2.createServer();
  // The child ends its connection by exiting.
  server.on("session", (session) => session.on("error", () => undefined));
  server.on("stream", (stream, headers) => {
    topics.push(String(headers["apns-topic"]));
    stream.on("data", () => undefined);
    stream.on("end", () => setTimeout(() => { stream.respond({ ":status": 200 }); stream.end(); }, 300));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  host = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));

describe("apnsSender", () => {
  // The Lambda's process has nothing else to wait on while a push is in flight
  // (kiff/tafs#138, item 32): an unref'd connection let Node exit before
  // Apple answered, and the alert was never handed to kiff/agents.
  it("keeps the process alive until Apple answers", async () => {
    const dir = mkdtempSync(join(tmpdir(), "apns-"));
    const pem = generateKeyPairSync("ec", { namedCurve: "P-256" }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
    writeFileSync(join(dir, "main.ts"), `
      import { apnsSender } from ${JSON.stringify(join(__dirname, "../src/cloud/apns.ts"))};
      const sender = apnsSender({ keyPEM: async () => ${JSON.stringify(pem)}, keyID: "K", teamID: "T", topic: "dev.tafs", host: ${JSON.stringify(host)} });
      const out = await sender.send({ token: "t1", environment: "sandbox" } as never, { kind: "alert", payload: { aps: {} } } as never);
      console.log(out);
    `);
    await build({ entryPoints: [join(dir, "main.ts")], bundle: true, platform: "node", format: "esm", outfile: join(dir, "main.mjs"), logLevel: "silent" });
    // And once it has, the idle connection lets the process end by itself.
    const { stdout } = await run(process.execPath, [join(dir, "main.mjs")], { timeout: 5_000 });
    expect(stdout.trim()).toBe("sent");
  });

  // macOS 12's own app has its own bundle id: its token on the iPhone's topic is DeviceTokenNotForTopic, which would drop
  // it as gone. A Mac is sent on macTopic, and never on the iPhone's when there's none.
  it("sends a Mac on its own topic, and an iPhone on the app's", async () => {
    const dir = mkdtempSync(join(tmpdir(), "apns-"));
    const pem = generateKeyPairSync("ec", { namedCurve: "P-256" }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
    writeFileSync(join(dir, "main.ts"), `
      import { apnsSender } from ${JSON.stringify(join(__dirname, "../src/cloud/apns.ts"))};
      const config = { keyPEM: async () => ${JSON.stringify(pem)}, keyID: "K", teamID: "T", topic: "dev.tafs", host: ${JSON.stringify(host)} };
      const n = { kind: "background", payload: { aps: { "content-available": 1 } } } as never;
      const both = apnsSender({ ...config, macTopic: "dev.tafs.macos" });
      const out = [
        await both.send({ token: "t1", platform: "ios", environment: "sandbox" } as never, n),
        await both.send({ token: "t2", platform: "macos", environment: "sandbox" } as never, n),
        await apnsSender(config).send({ token: "t3", platform: "macos", environment: "sandbox" } as never, n),
      ];
      console.log(out.join(","));
    `);
    await build({ entryPoints: [join(dir, "main.ts")], bundle: true, platform: "node", format: "esm", outfile: join(dir, "main.mjs"), logLevel: "silent" });
    topics.length = 0;
    const { stdout } = await run(process.execPath, [join(dir, "main.mjs")], { timeout: 5_000 });
    expect(stdout.trim()).toBe("sent,sent,failed");
    expect(topics).toEqual(["dev.tafs", "dev.tafs.macos"]);
  });
});

// Every push answered 429 TooManyProviderTokenUpdates once each tafs cloud instance (a cold
// start, a deploy) minted its own provider token, and pushes sent together could each mint one. Apple refuses a key whose
// token changes more often than every 20 minutes. Now one token serves every instance until it's TOKEN_MINUTES old.
describe("apnsSender's provider token", () => {
  let apple: http2.Http2Server;
  let at = "";
  /** Each push's bearer, as Apple got it. */
  const bearers: string[] = [];
  /** Bearers Apple refuses as one update too many. */
  const refused = new Set<string>();
  const open = new Set<http2.ServerHttp2Session>();
  beforeAll(async () => {
    apple = http2.createServer();
    apple.on("session", (session) => {
      session.on("error", () => undefined);
      open.add(session);
      session.on("close", () => open.delete(session));
    });
    apple.on("stream", (stream, headers) => {
      const bearer = String(headers.authorization).replace(/^bearer /, "");
      bearers.push(bearer);
      stream.on("data", () => undefined);
      stream.on("end", () => {
        if (refused.has(bearer)) {
          stream.respond({ ":status": 429, "content-type": "application/json" });
          stream.end(JSON.stringify({ reason: "TooManyProviderTokenUpdates" }));
        } else {
          stream.respond({ ":status": 200 });
          stream.end();
        }
      });
    });
    await new Promise<void>((resolve) => apple.listen(0, "127.0.0.1", resolve));
    at = `http://127.0.0.1:${(apple.address() as AddressInfo).port}`;
  });
  // The senders keep their connections open, as in a Lambda: close them, or the server waits on them.
  afterAll(() => new Promise<void>((resolve) => {
    for (const session of open) session.destroy();
    apple.close(() => resolve());
  }));

  const pem = generateKeyPairSync("ec", { namedCurve: "P-256" }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const device = { token: "t1", platform: "ios", environment: "sandbox" } as never;
  const n = { kind: "alert", payload: { aps: {} } } as never;

  /** The table's one item, as lambda.ts keeps it: swapped only if it's still the token read. */
  function memoryTokens() {
    let kept: ProviderToken | undefined;
    const store: TokenStore & { swaps: number } = {
      swaps: 0,
      async get() { return kept; },
      async swap(prev, next) {
        if (kept?.at !== prev?.at || kept?.value !== prev?.value) return false;
        kept = next;
        store.swaps++;
        return true;
      },
    };
    return store;
  }

  const sender = (tokens: TokenStore | undefined, now: () => number) =>
    apnsSender({ keyPEM: async () => pem, keyID: "K", teamID: "T", topic: "dev.tafs", host: at, tokens, now });

  it("pushes sent together wait on one token, and every instance uses the same one", async () => {
    bearers.length = 0;
    const tokens = memoryTokens();
    const clock = () => 1_700_000_000_000;
    const a = sender(tokens, clock);
    const b = sender(tokens, clock);
    const out = await Promise.all([...Array.from({ length: 8 }, () => a.send(device, n)), ...Array.from({ length: 4 }, () => b.send(device, n))]);
    expect(out.every((o) => o === "sent")).toBe(true);
    expect(new Set(bearers).size).toBe(1);
    expect(tokens.swaps).toBe(1);
    expect(bearers[0]).toBe((await tokens.get())!.value);
  });

  it("is used until it's TOKEN_MINUTES old, then replaced once, never sooner", async () => {
    bearers.length = 0;
    const tokens = memoryTokens();
    let t = 1_700_000_000_000;
    const a = sender(tokens, () => t);
    await a.send(device, n);
    t += 20 * 60_000;
    // A new instance at 20 minutes: the shared token, not its own.
    await sender(tokens, () => t).send(device, n);
    t += (TOKEN_MINUTES - 20) * 60_000 - 1;
    await a.send(device, n);
    expect(new Set(bearers).size).toBe(1);
    t += 2;
    await a.send(device, n);
    expect(new Set(bearers).size).toBe(2);
    expect(tokens.swaps).toBe(2);
  });

  it("an instance that loses the race to replace the token uses the winner's", async () => {
    bearers.length = 0;
    const t = 1_700_000_000_000;
    // The winner's token, as another instance kept it.
    const winner = memoryTokens();
    await sender(winner, () => t).send(device, n);
    const theirs = (await winner.get())!;
    bearers.length = 0;
    // This instance read no token, minted one, and another kept theirs first: its swap fails, and it reads theirs.
    let reads = 0;
    const racing: TokenStore = { get: async () => (reads++ === 0 ? undefined : theirs), swap: async () => false };
    expect(await sender(racing, () => t).send(device, n)).toBe("sent");
    expect(bearers).toEqual([theirs.value]);
  });

  it("a token Apple refuses as too many updates is sent again, once, with the shared one", async () => {
    bearers.length = 0;
    refused.clear();
    const t = 1_700_000_000_000;
    // The shared token, made by another instance.
    const tokens = memoryTokens();
    await sender(tokens, () => t).send(device, n);
    const shared = (await tokens.get())!;
    // This instance made its own while the store was down; then the store answers again.
    let down = true;
    const store: TokenStore = {
      get: async () => { if (down) throw new Error("down"); return shared; },
      swap: async () => { if (down) throw new Error("down"); return false; },
    };
    const a = sender(store, () => t + 60_000);
    bearers.length = 0;
    expect(await a.send(device, n)).toBe("sent");
    const mine = bearers[0]!;
    expect(mine).not.toBe(shared.value);
    down = false;
    // Apple refuses this instance's own token: the push goes again with the shared one, and it keeps that one after.
    refused.add(mine);
    bearers.length = 0;
    expect(await a.send(device, n)).toBe("sent");
    expect(bearers).toEqual([mine, shared.value]);
    bearers.length = 0;
    expect(await a.send(device, n)).toBe("sent");
    expect(bearers).toEqual([shared.value]);
    // Refused with nothing else to send: failed, sent once.
    refused.add(shared.value);
    bearers.length = 0;
    expect(await a.send(device, n)).toBe("failed");
    expect(bearers).toEqual([shared.value]);
    refused.clear();
  });
});
