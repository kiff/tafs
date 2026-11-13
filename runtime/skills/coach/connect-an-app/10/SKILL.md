---
name: connect-an-app
description: Connects an app for a taf: finding the app and its tools (search, events), asking before connecting, the planque request, the grant's ids and status, the owner's approval and sign-in, reconnecting. Use whenever a connection is made, read, approved or fails.
---

## When to use

Connects an app for a taf: finding the app and its tools (search, events), asking before connecting, the planque request, the grant's ids and status, the owner's approval and sign-in, reconnecting. Use whenever a connection is made, read, approved or fails.

## Procedure

- Before you propose any app, look at the account's connections: the lines
  after [tafs connections] in the conversation's first message are tafs'
  data, each with its vendor, the taf that uses it and its kind
  (vendor_sign_in, provider with its mode, key, browser). Without them, planque
  grants with no agent lists the same, each with its agent (a taf's
  kiff_agent in tafs_state). Offer only one a taf uses, or one made in this
  conversation; another agent's grant (a test's) is never offered or reused. For a vendor a taf already connects, the
  first option is reusing its sign-in, named with the taf that uses it: "your
  help desk's sign-in, like Refunds". Say a mode only as the grant gives it.
  Request {reuse: that grant's id, name, taf_name, tools: []}: the owner then
  approves the new taf's tools alone, with no new sign-in or key. Only if
  planque refuses the reuse, ask for the same kind: {server}, {entry} or {api}.
  Another kind comes only if the owner picks it: another sign-in.
- When the owner names no app for a need, find it before you ask to connect
  anything: search {q} with two or three short searches of words from the job
  ("overdue invoice") finds tools across every app the catalog can connect,
  each with its app and state. Propose the apps
  in a questions card, the ones the account already has first; an app that
  needs_setup can't be connected until an operator sets it up: say so and offer
  another. Connect the chosen app with request {entry, name, taf_name}, and no
  other field: the connection refuses a request with a field it doesn't take. Offer only what a connection can do: check the vendor has an operation
  (re-sending an invoice) with search {q} before connecting and catalog
  {grant, q} after. When the owner's
  answer can't be met, say why in one sentence and offer what can. An app the owner names is connected as they name it. events {entry}
  (before connecting) and events {grant} (after) list what can wake a taf:
  pick one as you pick an action, and draft it into the taf's dial. A write whose outcome comes later as an app event (a reply, a call that
  ends) needs the taf to wake on that event, or the connection refuses it as
  not_listening: draft the event into the dial with the write, in the same
  request.
- Ask before you connect a sign-in ("Is it OK if I connect to your help desk?").
  For a vendor's own MCP server, connect first, to look: planque request with
  its server, a name for the taf's agent (name, such as "reembolsos"), taf_name
  (the taf's name as the owner reads it), and no tools but your own setup reads (name-the-tools: the accounts list and
  the operations' fields), so one approval covers them. An app from search goes as above. Prefer a vendor's sign-in to its key, even
  when search finds both. A vendor with a REST API and no MCP server is connected
  by its key instead: planque request with api {url: its published OpenAPI
  description, base: where its operations' paths start, auth: where the key
  goes, as its docs say (in: header or query, name)}, in place of server. Use
  the description the owner or the vendor's docs point to; never make one up.
  The owner gives the key on a secure sheet on their phone, outside this chat,
  straight to planque: it never reaches you. Never ask for it in the chat, and
  if they paste one, don't use it: say it belongs on that sheet. For a key, the
  sheet is the ask, with no card before it. Set the taf's tools on the grant
  (planque tools) while it waits for its key; once its status says declared:
  true, agree the rules, draft the Card and show one approval with the grant
  and the draft: the sheet signs both. Not declared: the key, then the Card. planque's answer gives the taf's agent id: use it from then on. A tool's
  answer isn't kept between your turns: when you no
  longer have the taf's agent id or a grant's id, planque grants with no agent
  lists every connection you asked for, with its agent, status and when it
  was asked: take the newest for this vendor and this taf. Never connect again, make a new taf, or ask the owner to sign in again
  for an id you lost. Show an
  approval with the grant; the owner reads planque's own text, approves it
  and signs in on the vendor's own page (or, for a key, gives it on that sheet). You choose the name, from the
  taf's name: never ask the owner for it, or for any id.
Once a connection connects, or the owner reconnects one, a sign-in or key that
went through only means the vendor took it. Before saying it's ready or fixed,
check it: one read through it, yours (a setup read) or the taf's (taf_task,
"read one recent item, change nothing"), and its answer. A 401 or 403 there
is the key or sign-in refused: say so, and offer to connect it again. Only
when the read works, say it's ready; never that it is otherwise.
The owner's answer to a card comes back as the card's result, and you go on
from there: their choice in words ("Approved", "Declined", "Test mode"), or
what they wrote instead of answering. An approval or sign-in that didn't go
through says so, with why. It is their word on what they did, not proof. Read the
grant (planque, action status) before going on: only listed means you can read
its catalog, and only connected means the tools are in KIFF. Say a step is done
(signed in, connected, set up) only after the grant's status says so, never
before. If the sign-in was
left or failed, show a sign-in card for that same grant. A grant that is
needs_reconnect (its vendor signed planque out) gets a sign-in card for that
same grant, never a new planque request; its tools come back as they were once
the owner signs in and the grant says connected. Tell the owner in their words
("Sign in again to finish connecting it"), never these names. If the owner declined, ask what
they want instead. If an approval failed ("failed: …"), read the grant's status
first: the app's own error words are not a diagnosis to repeat. If planque says
the grant failed, say in one sentence that the connection didn't work and ask
to connect again, with a new planque request: a failed grant can't be approved.
If planque or KIFF refused the request itself (a tool name already connected, a
field or operation it won't take), change what was refused (another name, the
field it asks for) and show the approval of a new planque request: the same
request fails the same way.
Otherwise say it didn't go through and show the approval again (a new id). Never tell the owner to install, open or set up another app.
If planque answers that the owner's phone isn't set up yet, show a notice
(warning) asking them to start again from this screen, which sets the phone up
once; don't retry.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
