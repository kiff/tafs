// What the extension keeps (docs/chrome.md, "What's kept"): its key pair, whose private half can't be exported, and the
// pairing (this Chrome's id and name, and the phone's key). Nothing else is written to disk: no page, no step, no value
// read. Unpairing wipes all of it. A code being typed waits in chrome.storage.session, which Chrome keeps in memory only.

const DB = "tafs";
const STORE = "kept";

function open() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function run(mode, fn) {
  const db = await open();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, mode);
      const req = fn(tx.objectStore(STORE));
      tx.oncomplete = () => resolve(req && req.result);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

/** The kept state in IndexedDB. Tests use `memoryStore()` with the same shape. */
export const indexedStore = {
  get: (key) => run("readonly", (s) => s.get(key)),
  set: (key, value) => run("readwrite", (s) => s.put(value, key)),
  forget: (key) => run("readwrite", (s) => s.delete(key)),
  wipe: async () => {
    await run("readwrite", (s) => s.clear());
  },
};

export function memoryStore() {
  const map = new Map();
  return {
    map,
    get: async (key) => map.get(key),
    set: async (key, value) => void map.set(key, value),
    forget: async (key) => void map.delete(key),
    wipe: async () => map.clear(),
  };
}

/**
 * The id this Chrome profile keeps for itself (`profile_id`, docs/chrome.md "Your Chrome"): 16 random bytes as lower-case hex,
 * made once and kept in `area` (chrome.storage.local), which a pairing's wipe never touches, so the phone can tell the same
 * Chrome pairing again under a new key. It is not a secret and proves nothing. Not `chrome.storage.sync`: that would copy it
 * to every computer on the Google account, and two Chromes would look like one. A reinstall makes a new one.
 */
export async function profileId(area, random = (n) => crypto.getRandomValues(new Uint8Array(n))) {
  const kept = (await area.get("profile_id")).profile_id;
  if (typeof kept === "string" && /^[0-9a-f]{32}$/.test(kept)) return kept;
  const id = Array.from(random(16), (b) => b.toString(16).padStart(2, "0")).join("");
  await area.set({ profile_id: id });
  return id;
}

/**
 * What earlier versions kept as saved sign-ins (0.2.3 and before: the sealed entries and the key that sealed them), deleted.
 * The extension keeps no sign-in: at a sign-in page the taf hands the step to the person (docs/chrome.md, "What's kept"). Run
 * on every start, so an update deletes them before anything else, and a start with nothing to delete changes nothing.
 */
export const SAVED_SIGN_INS = ["vault", "vault_key"];
export async function forgetSavedSignIns(store) {
  for (const key of SAVED_SIGN_INS) await store.forget(key);
}
