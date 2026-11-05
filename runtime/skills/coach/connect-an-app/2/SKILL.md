---
name: connect-an-app
description: Connects an app for a taf: finding the app and its tools (search, events), asking before connecting, the planque request, the grant's ids and status, the owner's approval and sign-in, reconnecting. Use whenever a connection is made, read, approved or fails.
---

## When to use

Connects an app for a taf: finding the app and its tools (search, events), asking before connecting, the planque request, the grant's ids and status, the owner's approval and sign-in, reconnecting. Use whenever a connection is made, read, approved or fails.

## Procedure

- Before you propose any app, read the account's connections: planque grants
  with no agent lists each with its vendor, the taf's agent it serves and its
  kind (vendor_sign_in, the vendor's own sign-in on its page; provider, an app
  through a provider, with its mode; key; browser). For a vendor the account
  already connects, the first option is that same kind, named with the taf
  that uses it (its agent is that taf's kiff_agent in tafs_state): "your help
  desk's own sign-in, like Refunds". Say a mode only as the grant gives it; a
  mode it doesn't give isn't known. Ask for it the same way, for the new
  taf's agent: request {server} for vendor_sign_in, {entry} for provider,
  {api} for key. Another kind for that vendor comes after, only if the owner
  picks it: say it's another sign-in, whose mode isn't known until it's used.
- When the owner names no app for a need, find it before you ask to connect
  anything: search {q} with two or three short searches of words from the job
  ("overdue invoice", "send reminder email") finds tools across every app the
  catalog can connect, each with its app and the app's state. Propose the apps
  in a questions card, the ones the account already has first; an app that
  needs_setup can't be connected until an operator sets it up: say so and offer
  another. An app the owner names is connected as they name it. events {entry}
  lists what in an app can wake a taf, before connecting; events {grant} once
  connected: pick an event as you pick an action, and draft it into the taf's
  dial. A write whose outcome comes later, as one of the app's events (a reply
  the customer answers, a call that ends): the taf must wake on that event, or
  the connection refuses the call as not_listening, naming the event. Draft
  the event into the dial with the write, in the same request, so the taf
  waits for the outcome and goes on with it.
- Ask before you connect anything ("Is it OK if I connect to your help desk?").
  Then connect first, to look: planque request with the vendor's MCP server, a
  name for the taf's agent from the taf's name (name, such as "reembolsos"),
  and no tools. A vendor with a REST API and no MCP server is connected by its
  key instead: planque request with api {url: its published OpenAPI
  description, base: where its operations' paths start, auth: where the key
  goes, as its docs say (in: header or query, name)}, in place of server. Use
  the description the owner or the vendor's docs point to; never make one up.
  The owner gives the key on their phone as they approve, straight to planque:
  never ask for it in the chat, and if they paste one, say it's safer given on
  the phone and don't use it. planque's answer gives the taf's agent id, with a random part
  so it is new: use that id from then on, in the draft and the result. What a
  tool answered isn't kept from one of your turns to the next: when you no
  longer have the taf's agent id or a grant's id, planque grants with no agent
  lists every connection you asked for, each with its agent, server, status
  and when it was asked. Find it there, the newest for this vendor and this
  taf. Never connect again, make a new taf, or ask the owner to sign in again
  for an id you lost. Show an
  approval with the grant; the owner reads planque's own text, approves it
  and signs in on the vendor's own page (or, for a key, gives it there). You choose the name, from the
  taf's name: never ask the owner for it, or for any id.
When the owner asks you to reconnect a taf's vendor (it says its connection
isn't authorised, or its calls are refused), a sign-in that went through only
means the vendor took it, not that the taf can use it. Before saying it's
fixed, check it the way the taf works: give the taf a read through that vendor
(taf_task, "read one recent item, change nothing") and wait for its answer.
Only when that read works, say it's reconnected. If it still fails, say it
isn't fixed yet and what you'll try next, never that it is.
The owner's answer to a card comes back as the card's result, and you go on
from there: their choice in words ("Approved", "Declined", "Test mode"), or
what they wrote instead of answering. An approval or sign-in that didn't go
through says so, with why. It is their word on what they did, not proof. Read the
grant (planque, action status) before going on: only listed means you can read
its catalog, and only connected means the tools are in KIFF. Say a step is done
(signed in, connected, set up) only after the grant's status says so, never
before. If the sign-in was
left or failed, show sign_in for the same grant. A grant that is needs_reconnect
(its vendor signed planque out): show sign_in for that same grant, never a new
planque request; its tools come back as they were once the owner signs in and
the grant says connected. If the owner declined, ask what
they want instead. If an approval failed ("failed: …"), read the grant's status
first: the app's own error words are not a diagnosis to repeat. If planque says
the grant failed, say in one sentence that the connection didn't work and ask
to connect again, with a new planque request: a failed grant can't be approved.
If planque or KIFF refused the request itself (a tool name already connected, a
field or operation it won't take), change what was refused (another name, the
field it asks for) and show the approval of a new planque request: the same
request fails the same way, and the app won't send it again.
Otherwise say it didn't go through and show the same approval again (a new id)
so they can retry. Never tell the owner to install, open or set up another app.
If planque answers that the owner's phone isn't set up yet, show a notice
(warning) asking them to start again from this screen, which sets the phone up
once; don't retry.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
