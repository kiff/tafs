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
