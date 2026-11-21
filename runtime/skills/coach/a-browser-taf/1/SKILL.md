---
name: a-browser-taf
description: Sets up a taf on websites in the owner's Chrome, only when the connections offer it. Use when a job needs a website with no app.
---

## When to use

Sets up a taf on websites in the owner's Chrome, only when the connections offer it. Use when a job needs a website with no app.

## Procedure

1. **Only when offered.** Read the Chrome line after [tafs connections]. With no Chrome line, a taf in the owner's Chrome isn't offered: never propose it, and connect an app as usual.
2. **The sites are the owner's.** Ask which sites the job uses, and request exactly the hosts the owner named (shop.example). Never add one yourself, not a competitor, a search engine or a site you think helps. Asked for "and others", ask which ones. A bank, wallet, health or government site stays blocked until the owner allows it by name on the phone: say so, never work around it.
3. **The tools are the job's.** browse_open and browse_read to read. browse_act only when the job fills in or clicks. browse_checkout and browse_pay only when the owner asks the taf to pay: ask the currency, and say every pay waits for their approval on the phone, whatever the Card allows.
4. **No Chrome paired** ("offered, none paired yet"): say how, in two steps: on the phone, Account › Chrome › Pair a Chrome; in Chrome on the computer, add the tafs extension from the Chrome Web Store, open it and type the code the phone shows. Then wait for the owner. Never ask for the code, and never go on as if a Chrome were paired.
5. **Request it:** planque request {entry: "browser@1", name, taf_name, browser: {sites, currency?}, tools}, each tool {tool, title}. The owner picks which paired Chrome runs it, on the phone. You never pick or pair a Chrome, and never change which one a taf uses.
6. **The Card:** draft it as for any app. Its rules name the sites and what the taf does there. A taf that pays is held for every pay.
7. **Tell the owner what they'll see:** the taf opens its own tabs in a group they can see, pause or stop, and stops when they touch one. It reads pages as text, never their passwords, card or code fields.

## Verification

A browser taf is set up only when the owner approved its grant on the phone, with the sites and the Chrome it shows, and its Card is confirmed. A site the owner didn't name is never in the request.
