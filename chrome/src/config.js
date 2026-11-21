// This build's settings. planque's step key is pinned here, one per environment: a new key ships as a new build, and
// planque accepts only released builds (their manifest hashes). The production key is set when planque mints it; until
// then this build pairs with nothing.

export const ENVIRONMENTS = Object.freeze({
  production: {
    api: "https://planque.kiff.dev",
    relay: "wss://planque.kiff.dev/v1/extension",
    planqueKey: "", // planque's tafs-chrome step key (SPKI, base64url), pinned at release
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
    version: manifest.version,
  };
}
