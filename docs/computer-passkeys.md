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

- **A USB security key** (a FIDO2 key with a PIN), which the person plugs in and unlocks with its
  PIN for each approval. The app talks to the key over CTAP2, as Windows' own WebAuthn stack does,
  and builds the WebAuthn client data itself (below).
- **The person's phone:** the computer says to approve on the phone, and follows the phone's
  answer, as for any device that can't approve.

**A security key belongs to the person's account.** It's registered when the computer is paired
(a `kind: "passkey"` pairing whose QR names the key's credential, approved on the phone as any
pairing is, with the key's `credential_proof`). Once registered it's one of the person's passkeys:
listed in Devices → Passkeys, and removable there, as a phone's passkey is. It isn't bound to one
computer: anyone with the key and its PIN can use it, so the PIN is asked for each approval, and
the key is removed in Devices when it's lost.

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
   - for a computer's own platform passkey, one that is backup eligible or backed up (a synced
     passkey isn't that computer's own).
5. **The header:**
   `KIFF-Passkey-Assertion: t=<signed_at>,a=<base64url of {credential_id, client_data_json, authenticator_data, signature}>`,
   each field base64url with no padding. For two requests signed together, each header adds
   `with=sha256:<the other request's message hash>`.

KIFF checks the assertion against the passkey it holds for the person: the challenge is the
request's own message, the origin and relying party are kiff.dev's, the person was present and
verified, and the signature is the passkey's. It takes each approval once.

**The app never falls back to a device key** for an approval: a computer that can't produce the
passkey's assertion says to approve on the phone.

## Status

- Windows: built, behind a switch until its pairing makes the computer's passkey.
- The macOS 12 app and Linux: the message, the client data, the checks and the header are being
  built first, switched off. The USB security key comes next, then registering it through pairing.
