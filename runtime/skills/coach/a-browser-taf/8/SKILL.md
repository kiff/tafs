---
name: a-browser-taf
description: Sets up a taf on websites, hosted or in the owner's Chrome, when offered. Use when a job needs a website with no app.
---

## When to use

Sets up a taf on websites, hosted or in the owner's Chrome, when offered. Use when a job needs a website with no app.

## Procedure

1. **Only when offered.** Read the Hosted browser and Chrome lines after [tafs connections]. With neither, never propose a taf on websites: connect an app as usual. With a Hosted browser line, it runs there, never in the owner's Chrome: what follows about a Chrome (pairing, hand-over, tabs, the panel) is the owner's Chrome only.
2. **The sites are the owner's.** Ask which sites the job uses, and request exactly the hosts the owner named (shop.example). Never add one yourself, not a competitor, a search engine or a site you think helps. Asked for "and others", ask which ones. A bank, wallet, health or government site stays blocked until the owner allows it by name on the phone: say so, never work around it.
3. **The tools are the job's.** **Hosted browser, paying off,** first: no browse_pay and no currency, whatever the owner asks; don't ask for browse_checkout: with nothing to pay, the taf reads the page with browse_read. Its rules carry no payment line: nothing waits for a pay approval, no amount limit. A job that pays: say this browser never pays; the taf stops at the checkout, and the owner pays.
   browse_open and browse_read to read: a long page comes in parts, and a taf reads on until it has what it was asked. Its instructions never say how it reads, how much, or a page's size, and never tell it to stop at the first part. browse_act only when the job fills in or clicks. In the owner's Chrome, or a hosted browser that pays: browse_checkout and browse_pay only when the owner asks the taf to pay: ask the currency, and say every pay waits for their approval on the phone, whatever the Card allows.
   browse_hand and browse_hand_result only in the owner's own Chrome, and only for a step that is the person's to do in the page (a card number, a check, a click the taf mustn't make). The taf hands that step over in its own words, then reads their Done or Not now with browse_hand_result. It never does that step itself, and never learns what they typed.
4. **No Chrome paired** ("offered, none paired yet"): say how, in two steps: on the phone, Account › Chrome › Pair a Chrome; in Chrome on the computer, add the tafs extension from the Chrome Web Store, open it and type the code the phone shows. Then wait for the owner. Never ask for the code, and never go on as if a Chrome were paired.
5. **Request it:** planque request {entry: "browser@1", name, taf_name, browser: {sites, currency?}, tools}, each tool {tool, title}. In the owner's Chrome, they pick which paired Chrome runs it, on the phone; you never pick or pair a Chrome, and never change which one a taf uses. In the hosted browser, the request names no driver and the owner approves its sites on the phone.
6. **The Card:** draft it as for any app. Its rules name the sites and what the taf does there. A taf that pays is held for every pay. The taf's instructions never say what waits for the owner's approval or how often it may act: the Card says that, and a later version can change it.
7. **Where the taf starts:** its instructions open a site at the exact start page it was granted, https://<site>/ (https://shop.example/), never a name you put in front (en., m.) or a path you wrote. From there it opens only pages and links its last read gave: planque refuses any other address. One exception: a taf granted wikipedia.org opens an article directly, as https://<language>.wikipedia.org/wiki/<Title> (https://fr.wikipedia.org/wiki/Lyon), in the language the owner asks for, with nothing after the title: its instructions never send it through the home page or the search first.
8. **Hosted browser:** it works out of sight. The owner signs in to a site in this browser's live view in the app: say "this browser", never "your Chrome". It hands no other step over: a step that needs the owner stops the taf.
   **Tell the owner what they'll see (their Chrome):** the taf opens its own tabs in a group they can see, pause or stop, and pauses when they type, click or scroll in one, until they resume it. It reads pages as text, never their passwords, card or code fields. A step handed to them shows as Your turn in the extension's panel.
9. **The page they're on:** a message they send the taf from the extension's panel, on one of its sites, carries that page. Its instructions treat "this page" as that page, read first where it is, never a page they search for or a start page: never write "start at" for a job that works on the page the owner has open.

## Verification

A browser taf is set up only when the owner approved its grant on the phone, with the sites and the Chrome it shows, and its Card is confirmed. A site the owner didn't name is never in the request.
