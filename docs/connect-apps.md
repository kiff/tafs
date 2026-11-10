# Connect apps

A guide for the person running tafs: what connecting an app means, what you approve, and
what a taf can then do with it. The code and routes behind each step are in
[coach.md](coach.md#connect-apps), [api.md](api.md#connect-apps), and the app pages
([iPhone and iPad](ios-app.md), [Android](android-app.md#connect-apps)).

## What Connect apps is

A taf works in your business's own apps: your email, your shop, your accounting, your CRM, your
helpdesk, your team chat, your sheets and documents. **Connect apps**, in Settings, is where
you give a taf one of them.

The list shows what tafs can connect. The apps you've connected come first, with any that
need you to sign in again at the top. Type to search by name. Apps fall into kinds, for
example:

- **email and calendar**, such as Gmail, Outlook and Google Calendar;
- **documents and sheets**, such as Notion, Google Sheets, Google Drive, Excel and OneDrive;
- **team chat**, such as Slack and Teams;
- **shops and payments**, such as Shopify;
- **accounting and invoicing**, such as QuickBooks, Zoho Books and Moneybird;
- **CRM and support**, such as Pipedrive, Intercom and Gorgias;
- **forms**, such as Typeform.

An app shows on the list only once tafs can sign you in to it, so everything you see can be
connected.

## How connecting works

1. **Pick the app**, then the taf it's for. A connection belongs to one taf.
2. **Approve on your phone.** The approval says what you're connecting, in words: "Connect
   Gmail for Refunds. Your sign-in to Gmail stays with the connection service; the taf never
   sees it." You approve with Face ID or Touch ID, or your fingerprint on Android. Nothing
   happens before you do.
3. **Sign in on the app's own page.** Your password goes to that app, never to tafs.
4. **Confirm.** The app comes back to tafs, and the connection is ready for that taf.

Only someone who manages Cards on the account can connect an app.

### Apps that use a key

Some apps don't have a sign-in page. They use a key or a token you create in the app.

- **In the tafs sheet:** for some apps, you type the key on the approval sheet itself, and
  give it with Face ID or your fingerprint. It goes once to the vault that keeps your
  connections, sealed. The phone signs only a hash of it, and neither the phone nor tafs
  keeps it.
- **On the connection service's page:** for others, the approval tells you that you'll
  enter your key or token on the connection service's page, which keeps it. The taf never
  sees it either way.

The approval's words always say which one it is.

## What a taf can do with a connection

A connection gives the taf the app's actions: read an order, refund a payment, file an
invoice, post in a channel, add a row to a sheet. Coach picks the actions the taf's job
needs, and you approve them.

**The Card still decides every action.** Each call the taf makes goes through KIFF first,
and is checked against the taf's Card: what it may do, how much per action and in total,
and until when. Within the Card, it goes ahead. Anything over a limit waits for you in Needs
you, and anything the Card refuses is refused, whatever the app would allow. Two tafs
connected to the same app each work inside their own Card.

### Why the app's consent screen lists broad access

When you sign in, the app's own screen may ask for wide access: all your orders, your whole
mailbox, the field settings. That's the app asking for what the connection could ever use,
so you don't have to reconnect each time a taf's job grows. It isn't what a taf may do.
What a taf does is decided action by action by its Card.

## App events: a taf that wakes on something in an app

A taf can start when something happens in a connected app: a new deal, a new ticket, a
message in a channel. Coach sets it up in the taf's
[Setup › Starts when](taf-screen.md#setup--starts-when), with the event's
filters, for example "deals in the Sales pipeline over €5,000".

Each event needs **your approval of those exact filters**, with Face ID or your fingerprint.
Until you approve, the event shows **Approve** there, and nothing wakes
the taf. Once you have, the taf wakes only on events that match what you approved, and the
same event never starts it twice.

## The Slack door, or an action in Slack

Slack can be two different things for a taf:

- **A [door](doors.md#slack):** messages in one channel reach the taf as work. People you
  pair give it work, and anyone else knocks for your answer. The taf answers in the thread.
- **An action:** the taf posts in Slack as part of its job, for example a daily summary in
  a channel, as a place in the taf's Setup › Results go to. It's a call through KIFF like any other,
  inside the taf's Card.

Both use the taf's Slack connection.

## Disconnecting

A taf's connections are on its **Card** tab: whose sign-in, which actions, and what
happened last. **Disconnect**, with Face ID or your fingerprint, takes the app's actions
away from that taf at once, and only that taf: it asks first, “Disconnect Stripe from Refunds?”, and other tafs'
connections to the app stay connected. New actions stop through that connection; an action already sent may still
finish. When no other taf uses the same sign-in, it also asks the app to revoke it; that part may finish later, on the
app's side. If the app signs you out on its side (a
changed password, an expired session), the taf says so and waits for you: **Sign in again**
asks for one approval, then the app's own page.

### An app's connections, taf by taf

In Connect apps, an app you've connected (“Connected ✓”) opens its connections, one section
per taf. The tafs with a connection that needs you come first: a sign-in still waiting, an app
that signed you out, or a last call that failed. Each row says whose account it is and how
it's doing, and “Also on Chargebacks' Card” when another taf's Card uses it. If you manage
the account's Cards, a row has **Sign in again** and **Disconnect**, or **Cancel** on a
sign-in that never came back. Disconnect also names any other taf whose Card draws on that
connection, which stops too (“Chargebacks stops too.”).

**Other connections** comes last: connections the app can't put under a taf you see. It
doesn't say the taf was removed, because it may be one you don't see.

### Connections from removed tafs

When you remove a taf for good, its connections are disconnected with it, under the same
confirmation. If some couldn't be, the result says how many, and you can disconnect them
here.

Connections left by a taf that was removed earlier show under **From removed tafs**, with
that taf's name. **Clear these connections** ends the ones no live taf uses, after one
question (“Clear 2 connections from removed tafs?”) and one Face ID or fingerprint. A
connection another taf's Card still uses is kept, and says so (“Kept: Chargebacks' Card
uses it”). The line under them says how it went (“Cleared 1. Kept 1 for Chargebacks, whose
Card uses it.”). When tafs can't tell which ones are still used, there's no Clear.

How the apps build these lists is in [api.md](api.md#connect-apps),
[ios-app.md](ios-app.md#connect-apps) and [android-app.md](android-app.md#connect-apps).

## When an app isn't on the list

Ask Coach: **Not listed? Ask Coach** in Connect apps, or tell Coach what you need. Coach can
connect some apps that aren't on the list, and tells you when one isn't available yet.
