/**
 * tafs cloud's own keys in one secret (TAFS_SECRET_ID, JSON: `{apns, fcm, doors}`), read once per container. Until that
 * secret is proven live, a part it doesn't hold is read from its old secret (APNS_SECRET_ID, FCM_SECRET_ID,
 * DOORS_SECRET_ID), so moving the keys never takes one away. `apns` is the push key's PEM; `fcm` the Firebase service
 * account's JSON; `doors` the doors' provider keys, as an object.
 */
export type Part = "apns" | "fcm" | "doors";

/** Reads one secret's string in a region; undefined when it has none. */
export type ReadSecret = (id: string, region: string) => Promise<string | undefined>;

export interface AppSecret {
  /** A part as text (an object part as its JSON): the one secret's when it holds it, else its old secret's. */
  text(part: Part): Promise<string | undefined>;
  /** Whether a part can be read at all: the one secret is set, or the part's old secret is. */
  configured(part: Part): boolean;
}

export function appSecret(read: ReadSecret, env: Record<string, string | undefined>, regions: { agents: string; data: string }): AppSecret {
  const old: Record<Part, { id?: string; region: string }> = {
    apns: { id: env.APNS_SECRET_ID, region: regions.data },
    fcm: { id: env.FCM_SECRET_ID, region: regions.data },
    doors: { id: env.DOORS_SECRET_ID, region: regions.agents },
  };
  let whole: Promise<Record<string, unknown>> | undefined;
  function one(): Promise<Record<string, unknown>> {
    whole ??= (async () => {
      const id = env.TAFS_SECRET_ID;
      if (!id) return {};
      const raw = await read(id, env.TAFS_SECRET_REGION || regions.agents);
      try {
        const v: unknown = JSON.parse(raw ?? "{}");
        return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
      } catch {
        // A secret that isn't JSON holds no part: each part falls back to its old secret.
        return {};
      }
    })();
    return whole;
  }
  return {
    async text(part) {
      const v = (await one())[part];
      if (typeof v === "string" && v !== "") return v;
      if (v && typeof v === "object") return JSON.stringify(v);
      const o = old[part];
      return o.id ? read(o.id, o.region) : undefined;
    },
    configured(part) {
      return !!env.TAFS_SECRET_ID || !!old[part].id;
    },
  };
}
