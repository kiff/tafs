# Labels and the business description

Two light things Coach keeps for an account, in tafs cloud. Neither one is a container, and neither changes what a taf may do.

- **Labels** are chips on the tafs list, for the person to filter it by.
- **The business description** is "What tafs know about your business", the account's words about itself.

## Labels

**What a label is:**

- a name and a colour, nothing else. It holds no Card, permission, budget or context;
- on 0 to 3 tafs' rows each. An account has at most 12;
- Coach's to set, with `taf_labels`. The apps never change one: they only filter;
- removable. Removing a label changes nothing about its tafs: their Cards, connections, dial, hand-offs and runs stay as they were.

**Its name:** 1 to 24 characters on one line, unique in the account whatever its case.

- **A suggested label:** a name that means one of Explore's categories (Money, Customers, Operations, Errands) in any of the 8 languages carries that category as its `key`. It shows in each person's language, so "Geld" and "Money" are one label.
- **Any other name** shows exactly as named.

**Its colour:** one of the categories' colour tokens (`money`, `customers`, `operations`, `errands`), which the apps draw for light and dark. A suggested label takes its own category's colour. Any other takes the colour the fewest labels have.

**An empty label is dropped.** A label no taf carries never shows. A label is dropped from the account's list at the next change, so it doesn't count toward the 12.

### What the apps get

`GET /v1/home` ([api.md](api.md)) adds:

- `labels: [id]` on each taf that has any;
- `labels: [{id, name, color, count, waiting, headline: {words}}]` for the account, in the order to show them:
  - `count` is how many listed tafs carry it;
  - `waiting` is how many of those wait for the person, the amber dot;
  - `headline` is Home's first line filtered to it ("2 tafs working · Money");
- `labels_words: {all}`, the first chip ("All").

With no labelled taf, none of these is sent: the apps draw no filter row, and Home is as before.

The list opens on All every time. A taf's row shows two chips, then "+1". Filtering never reorders the list. Needs you's holds already name their taf, so the apps filter them with the same labels.

### Coach's tool

`taf_labels` has its own Card, `coach-labels`, at 100 changes a day. There's no fingerprint and no confirm card, since a label changes no authority. Coach says what it did in one line.

| Action | Takes | Does |
|---|---|---|
| `set` | `taf`, `labels` | The taf's whole list of labels, each an id or a name (`[]` for none). A new name makes the label |
| `rename` | `label`, `name?`, `color?` | Renames a label or changes its colour. Its id stays |
| `remove` | `label` | Takes it off every taf, then drops it |

**Refused, in words Coach can act on:**

- any other field, such as a Card, a tool, a dial or a limit. It's refused, never ignored;
- a 4th label on a taf, or a 13th on the account;
- a taf of another account, or a label the account doesn't have;
- in a person's chat, a taf KIFF doesn't say they manage.

Each write moves the account's labels on by one version. A write that lost to another tries again on what that one left. `tafs_state` lists each taf's labels.

## The business description

**What it is:** one per account, up to 300 words (2,400 characters), in the person's words. It says:

- what the company sells;
- who it sells to;
- its tone and languages;
- the links tafs use;
- its contact rules ("one first message per person").

**What it never holds:** a tool, a limit, an amount or personal data. It changes no Card.

**How it's made:** Coach drafts it with the person, from their answers and, where they agree, their public website (`web_read`). The person confirms it. Coach then saves it with `business_context`, which has its own Card, `coach-business`, at limit 0: **KIFF holds every save for the person's yes**, with the text shown. A Card with a limit above 0 would let that many saves through unasked each day.

- `read` answers `{text, version}`;
- `save {text, version}` keeps the confirmed draft over the version Coach read. It's refused when the description changed since, so Coach reads it again.

In a person's chat, saving is for someone who manages every taf of the account, that is, its owner and admins.

**In the apps:** `GET /v1/me/business` answers `{business: {text, version, words: {title, empty, change}}}`, in the person's language, for a read-only section in Account. Its "Change" opens Coach.

**What's next:** each taf's runs read it as a block marked data, never instructions. That's kiff/agents' preface, in the missions work that follows.

## Rules

1. **A label never widens anything.** No label route touches a Card, a connection or a dial, and each taf's Card is drafted from its own job.
2. **The description is words, never permissions.** A line in it that reads like an order changes nothing.
3. **Neither one exists in KIFF.**
