// What never leaves the page as read (docs/chrome.md, "Masking"): card numbers, secrets written in the page's text, and
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
    return out;
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

  root.TafsMask = { maskText, maskURL, luhn, MASK };
})(globalThis);
