# How a computer approves with a passkey

A phone approves with its approval key, behind Face ID, Touch ID or a fingerprint. A computer
approves the same actions, but not every computer has a key store the app can keep an approval
key in. This page says how each kind of computer approves, and what a passkey approval carries.

| Computer | How it approves |
|---|---|
| A Mac with Touch ID (macOS 13 and later, [mac-app.md](mac-app.md)) | Its own approval key, in the Secure Enclave, behind Touch ID, as an iPad. |
| A Windows computer | Its own platform passkey for kiff.dev, Windows Hello's, made when the computer is paired. |
| A Mac without Touch ID (the macOS 12 app), and Linux | **A USB security key**, or **the person's phone**. |

## A Mac without Touch ID, and Linux

These computers have no key store the app can keep an approval key in, and their systems can't
use the phone's passkey across devices (macOS 12 has no cross-device passkeys; macOS 13 added
them). So they approve in one of two ways:

- **A USB security key** (a FIDO2 key with a PIN), registered with the computer, which the person
  plugs in and unlocks with its PIN for each approval. The app talks to the key over CTAP2, as
  Windows' own WebAuthn stack does, and builds the WebAuthn client data itself (below).
- **The person's phone:** the computer says to approve on the phone, and follows the phone's
  answer, as for any device that can't approve.

**A security key goes with its computer.** It's registered when the computer is paired (a
`kind: "passkey"` pairing whose QR names the key's credential, approved on the phone as any
pairing is, with the key's `credential_proof`). KIFF lists it with that computer, as "Goes with its
computer", with no Remove of its own: signing that computer out in Devices removes it. The key
itself can be plugged into another computer, but it approves nothing there unless that computer is
paired with it. Its PIN is asked for each approval, and a lost key is removed by signing its
computer out.

The PIN sheet's words are the shared `approval.security_key.*` lines: the PIN, the touch, tries
left, and why the key gave no answer (none plugged in, more than one, a key that can't check a PIN, no PIN set,
a wrong PIN, three in a row, locked, another key, not touched in time, not allowed, unplugged).
Each failure says nothing was approved.
When the computer is paired, the same sheet adds the key instead (`add_title`, `add_enter_pin`, `add`), and
`pair_without` pairs the computer without one, as closing the sheet does.

## What a passkey approval carries

A passkey approves one exact request, or two requests signed together, never anything else.

1. **The message** is the one a device key would sign: `kiff.request.v1` for one request, or
   `kiff.requests.v1` for two signed together ([secure-connection.md](secure-connection.md)).
   Its time, `signed_at`, is RFC 3339 with milliseconds, and never the same twice.
2. **The challenge** is `base64url(sha256(message))`, so the assertion approves that message and
   nothing else.
3. **The client data** is
   `{"type":"webauthn.get","challenge":<the challenge>,"origin":"https://kiff.dev","crossOrigin":false}`,
   in that order. The authenticator signs `authenticatorData ‖ sha256(clientData)`.
4. **Before sending,** the app refuses what KIFF would refuse:
   - a relying-party hash that isn't `sha256("kiff.dev")`;
   - no user presence, or no user verification;
   - a credential other than the one asked for;
   - for a computer's own passkey, platform or security key, one that is backup eligible or
     backed up (a synced passkey isn't that computer's own).
5. **The header:**
   `KIFF-Passkey-Assertion: t=<signed_at>,a=<base64url of {credential_id, client_data_json, authenticator_data, signature}>`,
   each field base64url with no padding. For two requests signed together, each header adds
   `with=sha256:<the other request's message hash>`.

KIFF checks the assertion against the passkey it holds for the person: the challenge is the
request's own message, the origin and relying party are kiff.dev's, the person was present and
verified, and the signature is the passkey's. It takes each approval once.

**The app never falls back to a device key** for an approval: a computer that can't produce the
passkey's assertion says to approve on the phone.

**tafs cloud's signed routes take it too.** Remove for good (`DELETE /v1/tafs/{id}`), Discard
(`POST /v1/home/setting-up/{kiff_agent}/discard`), a drive's start (`POST /v1/coach/drive`) and an
approval's report (`POST /v1/tafs/{id}/components/{component}/approval/{ask}`) read
`KIFF-Passkey-Assertion` as well as `KIFF-Device-Signature`. tafs cloud sends it to KIFF's
`POST /v1/me/device-signature` as `passkey`, as it came, in place of `signature`. KIFF answers a
passkey as the approval key with no window, so each route's key rule holds as it is. A request
carrying both headers is refused before KIFF is asked (400, `signature_twice`), and one with
neither is unsigned (`signature_missing`, or the unsigned removal while `TAFS_SIGNED_REMOVE` is off).
A session that doesn't approve with a passkey gets KIFF's `passkey_not_this_session`, in tafs
cloud's words, and the same assertion twice gets 409 (`signature_taken`).

## Status

- Windows: built, behind a switch until its pairing makes the computer's passkey.
- The macOS 12 app and Linux: the message, the client data, the checks and the header are being
  built first, switched off. The USB security key comes next, then registering it through pairing.
