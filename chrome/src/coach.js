// Coach in the side panel (docs/chrome.md, "Coach in the side panel"): the same Coach chat the apps use, through tafs
// cloud's own routes, signed in as a view-only KIFF device of the account. It can read and send, never approve: KIFF
// refuses anything else for a view-only session. The panel sends the owner's words and the files they picked (attach.js). When Coach
// or a taf works on a page, its first step there is to read it itself, through planque.

export const CLOUD = "https://cloud.tafs.kiff.dev";

/** The longest message tafs cloud takes. */
export const MAX_TEXT = 2000;

/** tafs cloud's Coach routes, as the session's own: `token` is the view-only KIFF session's, `fetch` the browser's. */
export class Coach {
  constructor({ token, fetch = globalThis.fetch.bind(globalThis), base = CLOUD, lang = "en" }) {
    Object.assign(this, { token, fetch, base, lang });
    this.coachID = null;
  }

  async call(method, path, body) {
    const res = await this.fetch(this.base + path, {
      method,
      headers: { authorization: "Bearer " + this.token, accept: "application/json", "accept-language": this.lang, ...(body ? { "content-type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw Object.assign(new Error(data.words || data.error || "tafs cloud answered " + res.status), { status: res.status, code: data.code });
    return data;
  }

  /** The account's Coach, or null when it isn't started (Coach starts on the phone). */
  async coach() {
    if (this.coachID) return this.coachID;
    const { setup } = await this.call("GET", "/v1/setup");
    this.coachID = setup && setup.id ? setup.id : null;
    return this.coachID;
  }

  /**
   * The chat as the apps read it: the messages (owner's and Coach's), and whether Coach is at work. `session` names one
   * of the person's chats (`GET …/conversation/{session}`); none reads the live one.
   */
  async thread(session = null) {
    const id = await this.coach();
    if (!id) return null;
    const t = await this.call("GET", "/v1/tafs/" + encodeURIComponent(id) + "/conversation" + (session ? "/" + encodeURIComponent(session) : ""));
    return {
      messages: (t.messages || []).filter((m) => m && (m.from === "owner" || m.from === "taf") && typeof m.text === "string").map((m) => ({ from: m.from, text: m.text, at: m.at, session: m.session, ...(typeof m.id === "string" && m.id ? { id: m.id } : {}), ...(typeof m.reading === "string" && m.reading ? { reading: m.reading } : {}) })),
      working: t.working ? t.working.session : null,
      // A card that waits on the owner (an approval, a sign-in) is answered on the phone, never here.
      waitsOnPhone: Array.isArray(t.components) && t.components.some((c) => c && (c.type === "approval" || c.type === "sign_in") && !c.answer),
    };
  }

  /** The account's tafs as the person sees them (`GET /v1/tafs`): `{id, name, agent}`, an agent being KIFF's id for it. */
  async tafs() {
    const r = await this.call("GET", "/v1/tafs");
    return (r.tafs || [])
      .filter((t) => t && typeof t.id === "string" && t.id && typeof t.kiff_agent === "string" && t.kiff_agent)
      .map((t) => ({ id: t.id, name: typeof t.name === "string" ? t.name : "", agent: t.kiff_agent }));
  }

  /**
   * The account's tafs, read once for `key` (this Chrome's connections as the relay last said them) and kept: a chat's
   * reads and sends ask tafs cloud for none. A change of the connections reads them again, and `fresh` does too (the
   * panel shown). A read that fails isn't kept.
   */
  keptTafs(key, fresh = false) {
    if (fresh || !this.tafsKept || this.tafsKey !== key) {
      const read = this.tafs();
      this.tafsKey = key;
      this.tafsKept = read;
      read.catch(() => {
        if (this.tafsKept === read) this.tafsKept = null;
      });
    }
    return this.tafsKept;
  }

  /** A taf's own chat, as the phone reads it: the same shape as Coach's `thread`. */
  async tafThread(id) {
    const t = await this.call("GET", "/v1/tafs/" + encodeURIComponent(id) + "/conversation");
    return {
      messages: (t.messages || []).filter((m) => m && (m.from === "owner" || m.from === "taf") && typeof m.text === "string").map((m) => ({ from: m.from, text: m.text, at: m.at, ...(typeof m.id === "string" && m.id ? { id: m.id } : {}) })),
      working: t.working ? t.working.session : null,
      waitsOnPhone: Array.isArray(t.components) && t.components.some((c) => c && (c.type === "approval" || c.type === "sign_in") && !c.answer),
    };
  }

  /** Sends the owner's words, and the files they attached (ids, at most 5), to a taf's own chat. */
  async sendToTaf(id, words, clientID, attachments = []) {
    const text = String(words || "").trim().slice(0, MAX_TEXT);
    const files = fileIDs(attachments);
    if (!text && !files.length) throw Object.assign(new Error("nothing to send"), { code: "empty" });
    return this.call("POST", "/v1/tafs/" + encodeURIComponent(id) + "/messages", { text, client_message_id: clientID, ...(files.length ? { attachments: files } : {}) });
  }

  /**
   * Declares a file the person will attach to a message to taf `id` (Coach's when `id` is null): `{media_type, name, size,
   * sha256}`. tafs cloud answers its id and where to upload its bytes, which the panel does itself (attach.js).
   */
  async attach(id, file) {
    const taf = id || (await this.coach());
    if (!taf) throw Object.assign(new Error("Coach isn't started"), { code: "no_coach" });
    const body = { media_type: String(file.media_type || ""), name: String(file.name || "").slice(0, 200), size: Number(file.size), sha256: String(file.sha256 || "") };
    return this.call("POST", "/v1/tafs/" + encodeURIComponent(taf) + "/attachments", body);
  }

  /**
   * The person's chats with Coach, newest first, as the phone's Previous conversations lists them: `{session, title,
   * updated}`. A chat they set aside carries `set_aside` and isn't offered.
   */
  async chats() {
    const id = await this.coach();
    if (!id) return [];
    const r = await this.call("GET", "/v1/tafs/" + encodeURIComponent(id) + "/chats");
    return (r.chats || [])
      .filter((c) => c && typeof c.session === "string" && c.session && !c.set_aside)
      .map((c) => ({ session: c.session, title: typeof c.title === "string" ? c.title : "", updated: c.updated }));
  }

  /** The chats the person archived, newest first, in the same shape: the list to put one back from. */
  async archivedChats() {
    const id = await this.coach();
    if (!id) return [];
    const r = await this.call("GET", "/v1/tafs/" + encodeURIComponent(id) + "/chats/archived");
    return (r.chats || [])
      .filter((c) => c && typeof c.session === "string" && c.session)
      .map((c) => ({ session: c.session, title: typeof c.title === "string" ? c.title : "", updated: c.updated }));
  }

  /**
   * Archives one of the person's chats with Coach (`POST …/chats/{session}/archive`), or puts it back (`…/unarchive`): it
   * leaves their list and is kept, never deleted, so `archived: false` undoes it. It gives nothing, so it's not signed, as on
   * the phone.
   */
  async archive(session, archived = true) {
    const id = await this.coach();
    if (!id) throw Object.assign(new Error("Coach isn't started"), { code: "no_coach" });
    if (typeof session !== "string" || !session) throw Object.assign(new Error("no such chat"), { code: "bad_chat" });
    return this.call("POST", "/v1/tafs/" + encodeURIComponent(id) + "/chats/" + encodeURIComponent(session) + "/" + (archived ? "archive" : "unarchive"));
  }

  /** The person's page consent at tafs cloud: `{given, version}` (Coach reading the page in front, docs/chrome.md). */
  async pageConsent() {
    const { consent } = await this.call("GET", "/v1/me/page-consent");
    return consent || { given: false };
  }

  /** They allow it, to `version`'s words, from this extension's own screen. */
  async allowPage(version) {
    const { consent } = await this.call("POST", "/v1/me/page-consent", { version });
    return consent || { given: false };
  }

  /** They withdraw it. */
  async withdrawPage() {
    const { consent } = await this.call("DELETE", "/v1/me/page-consent");
    return consent || { given: false };
  }

  /**
   * Sends the owner's words: into the live chat, into the chat `session` names, or,
   * with `newChat`, into a new one (the answer says its `session`).
   */
  async send(words, { clientID, session = null, newChat = false, page = null, attachments = [] } = {}) {
    const id = await this.coach();
    if (!id) throw Object.assign(new Error("Coach isn't started"), { code: "no_coach" });
    const text = String(words || "").trim().slice(0, MAX_TEXT);
    const files = fileIDs(attachments);
    if (!text && !files.length) throw Object.assign(new Error("nothing to send"), { code: "empty" });
    const body = { text, client_message_id: clientID };
    if (files.length) body.attachments = files;
    if (newChat) body.new_chat = true;
    else if (session) body.session = session;
    // The page in front, read on this send (aside.js), as data for this turn only: tafs cloud keeps its host, never its text.
    // A page goes only with words (tafs cloud's rule): files alone go without it.
    if (text && page && typeof page.url === "string" && typeof page.text === "string") {
      body.page = { url: page.url, title: String(page.title || ""), text: page.text, truncated: page.truncated === true };
    }
    const path = "/v1/tafs/" + encodeURIComponent(id) + "/messages";
    try {
      return await this.call("POST", path, body);
    } catch (e) {
      // tafs cloud refused the page (nothing started): the person's words go once more on their own, as a page that can't be
      // read never holds a message. The answer then names no page.
      if (!body.page || e.status !== 400 || e.code !== "page_refused") throw e;
      const { page: _dropped, ...words } = body; // eslint-disable-line no-unused-vars
      const sent = await this.call("POST", path, words);
      return { ...sent, page_refused: true };
    }
  }
}

/** The file ids a message names: strings, each once, at most 5. Anything else is left out. */
export function fileIDs(ids) {
  return [...new Set((Array.isArray(ids) ? ids : []).filter((x) => typeof x === "string" && /^att_[0-9a-f]{32}$/.test(x)))].slice(0, 5);
}

/**
 * One Coach per session token, kept: `make(token)` builds it, and a later call with the same token gets the same one, so the
 * account's Coach id read once (`GET /v1/setup`) lasts as long as the session instead of being read again on every call.
 */
export function reusing(make) {
  let token = null;
  let kept = null;
  return (t) => {
    if (!kept || t !== token) {
      kept = make(t);
      token = t;
    }
    return kept;
  };
}
