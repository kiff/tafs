# The secure connection sheet, and one signature per setup

An app that takes a key gets it on a sheet that looks and acts protected, and a setup ends
with one signature: the key and the taf's Card together. This is the contract. Each lane
says "I can build this" before any lane builds.

## What the owner sees

One sheet, full screen, outside the chat, from Coach's card (**Give the key on a secure
sheet**) or from Connect apps:

1. **The title:** the app's logo and name, with a lock: "Secure connection to Qonto".
2. **Three plain lines**, worked out from the grant by tafs cloud:
   - **what it may do:** "Read only: it can't pay, send or change anything in Qonto" when
     every one of the grant's tools is a read. Otherwise "It can change things in Qonto. Its
     Card decides each action". The phone never works this out;
   - **the key:** "Your key is sealed: it's never shown again, and the taf never sees it";
   - **leaving:** "You can disconnect it any time, in Connect apps".
3. **Paste the key**, a button, never a typing field. Once pasted, the sheet shows only the
   key's last 4 characters ("Key …ab12"), and the clipboard is cleared ("Clipboard cleared").
4. **Details**, collapsed: the technical lines. These are the specification's URL and its
   SHA-256, the host the key is sent to, the header that carries it, and the connection's own
   words from the connection server, verbatim. These are the words its digest covers.
5. **Its Card**, when the setup has one drafted: KIFF's own lines for the draft, as the
   approval card shows them today ("the 4 reads, 1,000 calls a day, then it asks you").
6. **One legal line:** "By connecting, you agree to the Terms and the Privacy Policy", each
   a link.
7. **One button**, **Connect**, behind one fingerprint (or Face ID). It gives the key and
   confirms the Card with one signature (below).
8. **The receipt**, on the sheet: "Qonto connected · key …ab12 · Disconnect". The last 4 are
   the phone's, from the paste. Nothing keeps them after the sheet closes, so the
   connections list says "Connected", without them.

The sheet stays above the keyboard (with a Paste button there is no keyboard, except when
the person writes in a field of their own, such as search).

## tafs cloud: the sheet's words, by the grant

**`GET /v1/connect/grants/{grant}/sheet`**, for whoever manages the account's Cards. With the
person's session, tafs cloud reads the grant from the connection server and the catalog
entry it came from, and answers in the request's `Accept-Language`:

```json
{ "vendor": { "name": "Qonto", "logo": "https://…", "host": "thirdparty.qonto.com" },
  "title": "Secure connection to Qonto",
  "lines": [
    { "kind": "reads_only", "text": "Read only: it can't pay, send or change anything in Qonto" },
    { "kind": "sealed", "text": "Your key is sealed: it's never shown again, and the taf never sees it" },
    { "kind": "disconnect", "text": "You can disconnect it any time, in Connect apps" } ],
  "paste": { "button": "Paste the key", "what": "Paste the API key you created in Qonto",
             "pasted": "Key …{last4}", "cleared": "Clipboard cleared" },
  "details": { "title": "Details", "lines": [
    { "label": "Specification", "value": "https://…/openapi.json" },
    { "label": "SHA-256", "value": "…" },
    { "label": "Sent only to", "value": "thirdparty.qonto.com" },
    { "label": "Header", "value": "Authorization" } ] },
  "card_title": "Its Card",
  "legal": { "text": "By connecting, you agree to the {terms} and the {privacy}.",
             "terms": { "words": "Terms", "url": "https://kiff.dev/terms" },
             "privacy": { "words": "Privacy Policy", "url": "https://kiff.dev/privacy" } },
  "button": "Connect",
  "receipt": { "text": "{vendor} connected · key …{last4}", "disconnect": "Disconnect" } }
```

- **The first line's `kind`** is `reads_only` only when the grant lists at least one tool
  and every one says `read_only: true`. Otherwise it's `may_change`, including a grant whose
  tools carry no flag. The phone draws `text`, and may use `kind` for an icon.
- **`{last4}` and `{vendor}`** are the only placeholders. The phone fills `{last4}` from the
  pasted key, and the key never leaves the phone except to the connection server, as today.
- **The legal links** are per language (`https://kiff.dev/de/terms`), as the apps' own legal
  links are.
- **The logo** is the catalog entry's, as Connect apps draws it. With none, `logo` is absent
  and the phone draws the name alone.
- 404 for a grant the connection server doesn't show this person, and 409 for one that takes
  no key.

## One signature: the key and its Card together

**Why two today:** a key grant is given with the approval key (`tafs.connect.v1|…`, a
fingerprint). Coach's Card for the taf is then a separate draft, confirmed with
`tafs.cards.v1|tenant|draft|version`. KIFF names that confirm `critical`, so it takes a
second fingerprint, on a second card.

**The shape:** when Coach's card carries both the grant and the Card's draft (the approval
component's `draft`, as it can today), tafs cloud's approval answer gives one ask, step
`key_and_cards`, with one message for the approval key:

```
tafs.setup.v1|{tenant}|{grant}|{digest}|{key_sha256}|{draft}|{version}
```

- **The phone** fills `key_sha256` from the pasted key, signs once behind the fingerprint,
  and makes two calls with the same signature:
  1. the key to the connection server, as today (`POST …/grants/{id}/key`, the key in
     `Planque-Vendor-Key`);
  2. then the draft's confirm to KIFF.
- **The connection server** checks the signature over the message it rebuilds from its own
  grant, digest and the key's SHA-256. It takes `draft` and `version` as they're written,
  without reading them.
- **KIFF** checks the same signature over the message it rebuilds from the tenant, the draft
  and its version, with the device's approval key. It takes `grant`, `digest` and
  `key_sha256` as they're written. **This is the combined confirm KIFF needs:** today's
  confirm takes only `tafs.cards.v1`.
- **Each service takes one signature once,** for that draft version and that grant digest.
- **If the confirm fails after the key went in,** the app is connected and the Card still
  waits. The card says so, and its retry confirms with the same signature, with no second
  fingerprint, while the draft's version holds.
- **Without a draft** (an app that signs in at the vendor, whose tools are known only after
  the sign-in), the two steps stay as they are.

**Coach** drafts the Card before the key sheet opens, from the grant's tools, which a key
grant lists before the key is given. That's a skill change, and it takes an Eval.

**Until this ships:** an approval card whose connection is already connected never repeats
the connection's text. tafs cloud marks it on the conversation (`connection: {done: true,
words: "Qonto is connected (read only)"}`), and the apps draw only that line and Its Card.

## Lanes

| Lane | What it builds |
|---|---|
| **tafs cloud** | The sheet route and its words in the 8 languages; the `key_and_cards` ask; the connected mark on a later card |
| **Connection server** | The key route takes a `tafs.setup.v1` signature |
| **KIFF** | The draft confirm takes a `tafs.setup.v1` signature |
| **Apps** | The sheet; Paste the key; one fingerprint, two calls; the receipt |
| **Coach** | The Card drafted before the key sheet (an Eval) |

## Tests

- **The first line says read only only when every tool is a read**, and `may_change` for a
  grant with a write tool, or with no flags.
- **No placeholder but `{last4}` and `{vendor}`**, and every line is in the 8 languages.
- **One fingerprint per key setup with a Card:** the phone signs one `tafs.setup.v1`
  message, and both services accept it once. A second use is refused by each.
- **A signature over another draft, version, grant, digest or key** is refused by the
  service whose part doesn't match.
- **The key never leaves the phone except to the connection server**, and the last 4 aren't
  kept after the sheet closes.
- **A card whose connection is already connected** shows only the done line and Its Card.
