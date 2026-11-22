// This build's settings. planque's step key is pinned here, one per environment: a new key ships as a new build, and
// planque accepts only released builds (their manifest hashes). The production key is pinned (planque's public step key). The relay is the WebSocket API's own
// domain (`relay.planque.kiff.dev`, no path: the domain maps onto stage `v1`).

export const ENVIRONMENTS = Object.freeze({
  production: {
    api: "https://planque.kiff.dev",
    relay: "wss://relay.planque.kiff.dev", // the WebSocket API's own domain, mapped onto its stage: no path
    // The picture kept of each act (docs/chrome.md, "Screenshots of a run's acts"): off until planque stores them and the
    // Privacy page says so. A build with it on is a new build.
    screenshots: false,
    planqueKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExS_lxBTwDdO8wf8ksu6kkIF8r3Z-Vp3tWZUKjlBjQezW0BhCCW_0tkGqRewu0qBL8KDggls0cjFF-NDcnAjH7g", // planque's tafs-chrome step key (SPKI, base64url): a public key
  },
});

export const ENV = "production";

export function config() {
  const env = ENVIRONMENTS[ENV];
  const manifest = globalThis.chrome && chrome.runtime && chrome.runtime.getManifest ? chrome.runtime.getManifest() : { version: "0.0.0" };
  return {
    api: env.api,
    relayURL: env.relay,
    planqueKey: env.planqueKey,
    screenshots: env.screenshots === true,
    version: manifest.version,
  };
}
