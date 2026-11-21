// Sealed sign-ins (docs/chrome.md, "Sealed values"): a sign-in the owner saves in the side panel, for one exact origin.
// The value is encrypted at rest with a key that can't be exported, and is opened only to fill one field of one step that
// the paired phone released with its own signature (protocol.checkRelease). The taf only ever sees a handle; planque and
// KIFF never hold the value. Unpairing wipes the vault with everything else.

import { randomId } from "./crypto.js";

const KEY = "vault_key";
const ENTRIES = "vault";

/** An origin as a sign-in is kept for: https, no path, lower case. Null when it isn't one. */
export function originOf(text) {
  try {
    const u = new URL(String(text).trim().includes("://") ? String(text).trim() : "https://" + String(text).trim());
    if (u.protocol !== "https:" || !u.hostname || u.username || u.password) return null;
    return u.origin.toLowerCase();
  } catch {
    return null;
  }
}

export class Vault {
  /** `store` is the extension's kept state (store.js), so a wipe takes the vault too. */
  constructor(store) {
    this.store = store;
  }

  async key() {
    let key = await this.store.get(KEY);
    if (!key) {
      key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
      await this.store.set(KEY, key);
    }
    return key;
  }

  async entries() {
    return (await this.store.get(ENTRIES)) || [];
  }

  /** What the side panel and a read may show: origin, username's presence and id. Never a value. */
  async list() {
    return (await this.entries()).map(({ id, origin, hasUsername }) => ({ id, origin, hasUsername }));
  }

  /** Saves a sign-in for `origin`. The username is sealed too: it may be an email, and it's only typed after a release. */
  async save(originText, username, password) {
    const origin = originOf(originText);
    if (!origin) throw new Error("origin");
    if (typeof password !== "string" || !password) throw new Error("password");
    const key = await this.key();
    const id = randomId("sec_");
    const seal = async (value, part) => {
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const data = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: bound(origin, id, part) }, key, new TextEncoder().encode(value)));
      return { iv, data };
    };
    const entry = { id, origin, hasUsername: !!username, username: username ? await seal(username, "username") : null, password: await seal(password, "password") };
    await this.store.set(ENTRIES, [...(await this.entries()).filter((e) => e.origin !== origin), entry]);
    return entry.id;
  }

  async remove(id) {
    await this.store.set(ENTRIES, (await this.entries()).filter((e) => e.id !== id));
  }

  /** The handles a page on `origin` can name: `sec_…:password`, and `sec_…:username` when one was saved. */
  async handlesFor(origin) {
    const out = [];
    for (const e of await this.entries()) {
      if (e.origin !== origin) continue;
      if (e.hasUsername) out.push(e.id + ":username");
      out.push(e.id + ":password");
    }
    return out;
  }

  /**
   * The value a handle names, only for `origin`: the caller has already checked the phone's release. The origin, the
   * entry and the part are bound into the encryption too, so a value moved to another origin's entry, another entry, or
   * swapped between an entry's username and password can't be opened.
   */
  async open(handle, origin) {
    const [id, part] = String(handle).split(":");
    const e = (await this.entries()).find((x) => x.id === id);
    if (!e || e.origin !== origin || (part !== "password" && part !== "username")) return null;
    const sealed = e[part];
    if (!sealed) return null;
    const key = await this.key();
    try {
      const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: sealed.iv, additionalData: bound(origin, id, part) }, key, sealed.data);
      return new TextDecoder().decode(plain);
    } catch {
      return null;
    }
  }
}

/** What a sealed value is bound to: its exact origin, its entry and which part it is. */
function bound(origin, id, part) {
  return new TextEncoder().encode(origin + "\n" + id + "\n" + part);
}
