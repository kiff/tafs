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
