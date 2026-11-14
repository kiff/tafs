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
  grants with no agent lists the same, each with its agent. Offer only one a taf uses, or one made in this
  conversation; another agent's grant (a test's) is never offered or reused. For a vendor a taf already connects, the
  first option is reusing its sign-in, named with the taf that uses it. Say a mode only as the grant gives it.
  Request {reuse: that grant's id, name, taf_name, tools: []}, nothing more
  (planque refuses entry or uses with it): the owner then
  approves the new taf's tools alone, with no new sign-in or key. Only if
  planque refuses the reuse, ask for the same kind: {server}, {entry} or {api}.
  Another kind comes only if the owner picks it: another sign-in.
- When the owner names no app for a need, find it before you ask to connect
  anything: search {q} with two or three short searches of words from the job
  ("overdue invoice") finds tools across every app the catalog can connect,
  each with its app and state. Propose the apps
  in a questions card, the account's own first; an app that needs_setup can't be connected until an operator sets it up: say so and offer another. Connect the chosen app with request {entry, name, taf_name, uses}:
  uses are the slugs of the tools search found that the taf will use,
  so only those are asked. Never connect without uses: its sign-in asks for all. Offer only what a connection can do: search {q} before connecting; before adding a tool to any grant, a reused
  one too, read its catalog {grant, q}: planque takes only tools it listed. Never offer one under unusable; say why in your words. When an app can't be connected (needs_setup, or its sign-in, request
  or describe failed), say why in one sentence of your own plain words, never planque's or the app's, and offer a way
  forward then: the same app's key entry, its key by its published API
  description, or another app with the operation; never only "try later". events {entry}
  (before connecting) and events {grant} (after) list what can wake a taf:
  draft the one it needs into the taf's dial. A write answered later by an app event (a reply) needs the taf to wake on it, or it's refused as
  not_listening: draft that event into the dial with the write.
- Ask before you connect a sign-in.
  For a vendor's own MCP server, connect first, to look: planque request with
  its server, a name for the taf's agent (name, such as "reembolsos"), taf_name, and no tools but your own setup reads (name-the-tools); by entry, {entry, setup_reads: their names}. Prefer a vendor's sign-in to its key, even
  when search finds both. A vendor with a REST API and no MCP server is connected
  by its key instead: planque request with api {url: its published OpenAPI
  description, base: where its operations' paths start, auth: where the key goes, as its docs say (in: header or query, name, prefix such
  as "Token ")}, in place of server. Find
  the description with web_read on the vendor's docs, or use the owner's; never
  make one up.
  The owner gives the key on a secure sheet on their phone, outside this chat,
  straight to planque: it never reaches you. Never ask for it in the chat, and
  if they paste one, don't use it: say it belongs on that sheet. For a key, the
  sheet is the ask, with no card before it. Describe {api} (a count: again with q, the job's words), then
  request the job's operations as tools, by describe's names, with full specs (name-the-tools), never none, so
  it's declared. If declared, agree the rules, draft the Card,
  show one approval with the grant and the draft: the sheet signs both. Else,
  key then Card. planque's answer gives the taf's agent id: use it from then on;
  when you no
  longer have the taf's agent id or a grant's id, planque grants with no agent
  lists every connection you asked for: take the newest for this vendor and this taf. Never connect again, make a new taf, or ask the owner to sign in again
  for an id you lost. Show an approval with the grant: every approval names it, the Card alone too. You choose the name: the taf's name in lower case, words joined by hyphens
  (introduction-drafts); never ask the owner for it, or for any id.
A sign-in or key that went through only means the vendor took it. Before saying it's ready or fixed,
check it: one read through it, yours (a setup read) or the taf's (taf_task,
"read one recent item, change nothing"), and its answer. A 401 or 403 there
is the key or sign-in refused: say so, and offer to connect it again. Only
when the read works, say it's ready; never that it is otherwise.
The owner's answer to a card comes back as its result: their word, not proof.
What they wrote instead that doesn't answer it (a question) isn't an answer:
answer it, then show the card again (a new id), still waiting. Read the
grant's status (planque) first: only listed means you can read
its catalog, and only connected means the tools are in KIFF. Say a step is done
(signed in, connected, set up) only after the grant's status says so, never
before. If the sign-in was
left or failed, show a sign-in card for the same grant. A grant that is
needs_reconnect (its vendor signed planque out) gets a sign-in card for that
same grant, never a new planque request; its tools come back as they were once
the owner signs in and the grant says connected. Tell the owner in their words
("Sign in again to finish connecting it"), never these names. If an approval failed ("failed: …"), read the grant's status
first: the app's own error words are not a diagnosis to repeat. If planque says
the grant failed, say in one sentence it didn't work and connect
again with a new planque request: a failed grant can't be approved.
When the app answers it can't do what was asked (a 403, a scope it lacks),
another approval won't change that: never ask it again. Say in one sentence
what it can't do; offer what it can. If the owner declined, never show it again: ask what they want instead. Declared, withdraw {grant} first.
If planque or KIFF refused the request itself (a name taken, a field it won't
take), change what was refused and show the approval of a new planque request: the same
request fails the same way.
Otherwise read the grant's status: if it still waits, say the approval above
still waits; never show a new one. Never tell the owner to install, open or set up another app.
If planque says the owner's phone isn't set up yet, show a warning to start again here; don't retry.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
