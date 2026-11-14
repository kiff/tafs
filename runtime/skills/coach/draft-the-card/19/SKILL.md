---
name: draft-the-card
description: Proposes a taf's rules and drafts its Card: the limits in plain words, held or refused, a day's total, an app set up on demand, the draft, read tools and money tools' amounts. Use when the rules are proposed or a Card is drafted.
---

## When to use

Proposes a taf's rules and drafts its Card: the limits in plain words, held or refused, a day's total, an app set up on demand, the draft, read tools and money tools' amounts. Use when the rules are proposed or a Card is drafted.

## Procedure

- Propose the rules in plain words before you set anything: what the taf may
  do, the limits (each action, and over a day), what it can never touch, and
  whether an action over a limit is held for the owner or refused. The limits
  are the owner's choice: you propose them and the owner decides. Never say they're yours. Over a limit, a call is held or refused as the recipe or
  the owner said: never turn a hold into a refusal, or a refusal into a hold.
  A limit for each item ("one invoice per order, ever") is a total with per:
  the tool's resource_argument (lifetime, or rolling_7d), beside the day's
  total. With none, it goes in the taf's instructions, and the rules say KIFF
  can't count it. Never a lifetime total on the tool: it stops after one item. Say, for each action, exactly what asks the owner first and
  what is refused, as the draft will have it: never "every action over a
  limit waits for you" when one is refused. Say which
  the owner can approve: one action over its own limit can be approved once;
  past a day's total, it waits until they raise the limit, or is refused:
  never "can't be approved". Never promise an approval the Card can't give. For a read tool,
  say the call count its Card line will have ("reads only, up to 1,000 a
  day"), never "no limit": every read tool on the Card has one, and the owner
  approves what the rules said.
  Show them
  as a notice (tone info, a short title, one short line per rule, and where it's
  kept if not on the Card: "the connection keeps it", "KIFF adds it", "in its
  instructions"), then a
  confirm with two options only, Agree and Change something (the message stays
  one sentence); never another rule as an option: ask it as its own question
  first, and put the answer in the rules. A
  limit the owner already gave, in their first message or later, is that
  limit: put it in the rules as they said it, never another in its place.
  Ask about each limit they didn't give. Then set the tools
  on the grant (planque tools). When planque says declared: true, draft the Card
  at once, in the grant's approval, without saying so; else the grant's, then
  the Card's. Draft it once, with all its tools, from the catalog's fields (or the describing tool's): the approval that adds its tools carries the Card, never a Card before its tools are known. A day is the account's: say so as a rules line ("a day ends at midnight in your account's time zone"), not in a message.
- A grant whose status says on_demand: true is an app whose sign-in was set up
  on the owner's pick, not reviewed by an operator: draft every tool of it that
  changes data or moves money to ask the owner each time (0 a day, held; a money tool still names an amount limit), reads open, and say
  it as one line of the rules notice ("{app} was set up today: every change asks
  you until you widen it").  Say who
  holds the sign-in as the grant's custody says, in the owner's words ("your
  sign-in is kept by our sign-in partner"), never a service's name.
- Only when planque says connected or declared: true, give the taf its Card: submit its draft
  (kiff_draft) over the connected tools, with the limits you agreed, then show a
  new approval with the grant and the draft. The draft is the agreed rules,
  line for line, never merged ("1,000 reads a day each" stays a line per
  tool, not 4,000 for all). A limit it can't hold as agreed is a changed rule:
  show it in a new rules notice and get it agreed first. Never change a number
  silently. Each tool's on_exceed is its agreed line's: owner only where it
  says the owner is asked, else refuse. Every tool the taf gets goes on
  its Card, the read tools too: KIFF refuses any call no Card covers. That includes the read a money tool's
  unit_lookup names (the read that says the payment's currency): KIFF makes that
  read as the taf, so its Card covers it like any other. A draft leaving a
  connection's tool uncovered isn't signed. A read tool's line has only a generous call count; never ask the owner to set it.  Name the
  tools in the draft as planque's status lists them (their names in KIFF, such
  as post_reply_k3f9). The draft names
  only the tools planque lists for this taf's own grant, and of those never
  one you asked for with setup_reads (a tool that lists operations' fields, or
  the list of accounts): those are your reads, on your reader Card; the taf never gets them. Never another tool
  KIFF knows on the account (another taf's, or one an admin connected for
  another agent). A tool that
  moves money always has its amount limited, each call and over a day: KIFF
  refuses a draft that counts a money tool's calls without limiting its amount.
  Count a money tool's calls only when the owner did.
  A tool that writes to anyone outside the company (an email, a reply), or drafts for them (a draft email, a draft invoice), goes in the result's taf as disclose: [{tool: its name in KIFF,
  fields: its text fields' paths, from planque read}]: KIFF's runtime then
  starts each such call with "Written by an AI agent on behalf of <writes_for>.", in
  the message's language when it's Spanish, French, German, Portuguese or
  Italian.
- A taf that writes to customers sends, by default, from a mailbox the
  company already has, through its own mail app's connection (search {q}
  for sending email), so mail comes from its address and replies land
  where its staff work. Suggest a dedicated mailbox (support@, a shared or
  a separate one), never a person's own inbox: the app's access covers the
  whole mailbox. A connection the account has comes first only when it is
  that dedicated mailbox (its lines don't say which mailbox: ask, with your
  other questions); one that is a person's own inbox is never reused for
  customer mail: suggest connecting the dedicated one. Ask only for what
  the taf does (uses: the send tool alone when it only sends). An agent mail
  app's inbox stays an option.
- A taf that writes to people: ask once, with your other questions, who its
  messages come from: first "tafs, your AI assistant" (it speaks as itself:
  writes_for "tafs"), then the owner's name, then the company's ("Kiff Agents
  OÜ"); never the app's default. Put the answer in the result's taf as
  writes_for. If the mail app can, set the inbox's sender name to it with its own tool; if it sends from its own domain and takes a custom one, offer the company's in the same question: show its DNS records, and check them with its tool before saying it works.
  A tool that changes data but moves no money is limited by calls a day ("up to 20 replies a day"), never by an amount: its call carries none.

## Verification

What this skill says is done only when the owner saw it: the card shown, the result or version answered by tafs, the grant's status read. Nothing is done because it was said.
