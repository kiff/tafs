// What never leaves the page as read (docs/chrome.md, "Masking"): card numbers, secrets and one-time codes written in the page's text, and
// credential parameters in its links. planque masks again whatever the extension sends (internal/browser Mask).
//
// No import or export here: the content script loads this file as a classic script before page.js, and the service worker
// and the tests import it as a module. Either way it sets `globalThis.TafsMask`.

(function (root) {
  const CARD_RUN = /\b(?:\d[ -]?){12,18}\d\b/g;

  function luhn(digits) {
    let sum = 0;
    let double = false;
    for (let i = digits.length - 1; i >= 0; i--) {
      let d = digits.charCodeAt(i) - 48;
      if (double) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      double = !double;
    }
    return sum % 10 === 0;
  }

  const MASK = "[masked]";

  // Secrets by their shape: keys and tokens services write in a known form.
  const SHAPES = [
    /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g,
    /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, // JWT
    /\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{16,}\b/g, // Stripe
    /\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}\b/g, // model providers
    /\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\b/g,
    /\bgithub_pat_[A-Za-z0-9_]{40,}\b/g,
    /\bxox[abprs]-[A-Za-z0-9-]{10,}\b/g, // Slack
    /\bAKIA[0-9A-Z]{16}\b/g, // AWS access key id
    /\bAIza[0-9A-Za-z_-]{35}\b/g, // Google API key
    /\bglpat-[A-Za-z0-9_-]{20,}\b/g,
  ];

  // A labelled value: "password: hunter2", "api_key=…", "Bearer …". The label stays; the value goes.
  // A label needs ":" or "=" after it, so prose ("password incorrect") stays as it is.
  const LABELLED = /\b(password|passwd|pwd|passcode|pin|secret|client[_ -]?secret|api[_ -]?key|access[_ -]?key|access[_ -]?token|refresh[_ -]?token|auth[_ -]?token|token|cookie|session[_ -]?id)(\s*[:=]\s*)("[^"\n]{4,}"|'[^'\n]{4,}'|[^\s"',;<>]{4,})/gi;
  const BEARER = /\b(Bearer\s+)[A-Za-z0-9._~+/-]{10,}=*/g;

  // A one-time code written in the page's text ("Your verification code is 482913", "123456 is your login code", "votre
  // code est 4821", "tu código es 4821", "Ihr Code lautet 4821"): the digits go, the words stay. A code is 4 to 8 digits, or
  // two groups of 3 or 4 joined by a space (a non-breaking or thin one too) or a dash. A postal, order or promo code stays. tafs cloud masks the same in a
  // page Coach reads (runtime/src/cloud/page-codes.ts): the cases in runtime/test/fixtures/one-time-codes.json hold both.
  const CODE_DIGITS = "(?<!\\d)(\\d{3,4}[ \\u00a0\\u2009\\u202f-]\\d{3,4}|\\d{4,8})(?!\\d)";
  const CODE_NAMED = "(?:verification|security|confirmation|authentication|login|log-in|sign-in|signin|one-time|one time|access|2fa|mfa|otp)\\s+code" +
    "|one-time\\s+password|otp|passcode|code\\s+(?:de\\s+(?:vérification|verification|confirmation|sécurité|securite|connexion|validation)|à\\s+usage\\s+unique)" +
    "|código\\s+de\\s+(?:verificación|verificacion|confirmación|confirmacion|seguridad|acceso|validación|validacion|un\\s+solo\\s+uso)" +
    "|(?:bestätigungs|verifizierungs|sicherheits|anmelde|einmal|authentifizierungs)code";
  const CODE_OTHER = "(?<!\\b(?:postal|zip|post|area|country|promo|discount|coupon|gift|tracking|product|error|status|order|source|tax|reference|ref)\\s)";
  const CODE_WORD = "(?:code|código|codigo)";
  const CODES = [
    // A code named for what it is, then the digits within a few words, on its line or the next: "verification code for Acme: 482913".
    new RegExp(`(\\b(?:${CODE_NAMED})\\b[^\\d]{0,40}?)${CODE_DIGITS}`, "giu"),
    // A bare "code" only with "is" or ":" right after it: "your code is 4821", "code: 4821".
    new RegExp(`(\\b(?:(?:your|votre|ton|tu|su|ihr|dein)\\s+)?${CODE_OTHER}${CODE_WORD}\\s*(?:(?:is|est|es|ist|lautet)\\s*:?|[:=])\\s*)${CODE_DIGITS}`, "giu"),
  ];
  // The digits first: "482913 is your Acme verification code", "G-482913 est votre code".
  const CODE_FIRST = new RegExp(`${CODE_DIGITS}(?=\\s+(?:is|est|es|ist)\\s+(?:your|votre|ton|tu|su|ihr|dein)\\b[^\\n]{0,40}?(?:code|código|codigo|otp|passcode|password)\\b)`, "giu");

  /** Text with each one-time code's digits replaced, its words kept. */
  function maskCodes(text) {
    let out = String(text);
    for (const re of CODES) out = out.replace(re, (all, lead) => lead + MASK);
    return out.replace(CODE_FIRST, MASK);
  }

  /** Text with card numbers cut to their last four digits and secrets by shape or label replaced. */
  function maskText(text) {
    if (!text) return text || "";
    let out = String(text).replace(CARD_RUN, (run) => {
      const digits = run.replace(/[^0-9]/g, "");
      if (digits.length < 13 || digits.length > 19 || !luhn(digits)) return run;
      return "•••• " + digits.slice(-4);
    });
    for (const shape of SHAPES) out = out.replace(shape, MASK);
    out = out.replace(LABELLED, (all, label, sep, value) => (value === MASK ? all : label + sep + MASK));
    out = out.replace(BEARER, (all, lead) => lead + MASK);
    return maskCodes(out);
  }

  // A long run that is probably a key: 32 or more hex digits, or base64 characters with at least one digit in them.
  const LONG_HEX = /\b[A-Fa-f0-9]{32,}\b/g;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i; // an id, not a key
  const LONG_BASE64 = /[A-Za-z0-9+/_-]{32,}={0,2}/g;

  /**
   * Where in `text` something that must not show in a picture is: `[start, end]` pairs, in order, merged. It is what `maskText`
   * would replace (card numbers that pass the Luhn check, secrets by shape, labelled values, bearer tokens, one-time codes) and, for a picture
   * only, long hex or base64 runs of 32 characters or more.
   */
  function secretSpans(text) {
    const s = String(text || "");
    const found = [];
    const add = (re, from) => {
      for (const m of s.matchAll(re)) {
        const [start, end] = from ? from(m) : [m.index, m.index + m[0].length];
        if (end > start) found.push([start, end]);
      }
    };
    add(CARD_RUN, null);
    // A card run counts only when it is a card: 13 to 19 digits passing the Luhn check.
    for (let i = found.length - 1; i >= 0; i--) {
      const digits = s.slice(found[i][0], found[i][1]).replace(/[^0-9]/g, "");
      if (digits.length < 13 || digits.length > 19 || !luhn(digits)) found.splice(i, 1);
    }
    for (const shape of SHAPES) add(shape, null);
    add(LABELLED, (m) => [m.index + m[1].length + m[2].length, m.index + m[0].length]);
    add(BEARER, (m) => [m.index + m[1].length, m.index + m[0].length]);
    for (const re of CODES) add(re, (m) => [m.index + m[1].length, m.index + m[0].length]);
    add(CODE_FIRST, null);
    add(LONG_HEX, null);
    add(LONG_BASE64, (m) => (/[0-9]/.test(m[0]) && !UUID.test(m[0]) ? [m.index, m.index + m[0].length] : [0, 0]));
    found.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    const merged = [];
    for (const span of found) {
      const last = merged[merged.length - 1];
      if (last && span[0] <= last[1]) last[1] = Math.max(last[1], span[1]);
      else merged.push([span[0], span[1]]);
    }
    return merged;
  }

  // Query parameters that carry a credential, by name, compared after percent-decoding.
  const CREDENTIAL_PARAMS = /^(token|access_token|id_token|refresh_token|auth|authorization|code|state|signature|sig|key|api_key|apikey|password|pass|pwd|secret|session|sessionid|sid|otp|x-amz-signature|x-amz-credential|x-goog-signature)$/i;

  /** A link's URL with the values of credential parameters and any user info removed. "" when it isn't https. */
  function maskURL(raw) {
    let u;
    try {
      u = new URL(raw);
    } catch {
      return "";
    }
    if (u.protocol !== "https:") return "";
    u.username = "";
    u.password = "";
    const params = [...u.searchParams.keys()];
    for (const name of params) {
      if (CREDENTIAL_PARAMS.test(name)) u.searchParams.set(name, MASK);
    }
    if (u.hash && /(access_token|id_token|token|code)=/i.test(u.hash)) u.hash = "";
    return u.href;
  }

  root.TafsMask = { maskText, maskCodes, maskURL, secretSpans, luhn, MASK };
})(globalThis);
