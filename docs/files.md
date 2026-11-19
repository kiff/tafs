# Files

What the account's tafs deliver in their runs (a report, a sheet, a document), kept so the people on the account can open,
save and share them. From the owner-approved mockup v2.1. **On for iPhone and iPad since 19 Nov** (`FilesFeature.on`), on the
owner's yes, once the privacy lines were live in 8 languages and the phone cleared its own copies on sign-out (tafs#1467). The
screens read tafs cloud's routes (`CloudFilesStore`), in KIFF mode only; tests read `StubFilesStore`.

## The owner's rules

- **Kept 30 days from the run**, then gone on its own (`FilesFeature.keptDays`). tafs cloud decides when a file goes and says
  it (`goesAt`); the app only counts the days to that date (`FilesGrouping.daysLeft`), never below none.
- **Share sends the file itself** through the device's share sheet. There's no public link.
- **Everyone on the account** sees the account's files.
- **Delete now**, behind a confirm, deletes for everyone on the account, at once. The file leaves the list only once the
  store has deleted it (`FilesModel.delete`).
- **No tab of its own:** a Files row in Account, the Files entry on each taf's header, and under the chat the files a
  run delivered.

## The model (`ios/Tafs/Model/Files.swift`)

- `DeliveredFile`: the file (name, type, size), the taf that made it (by id, and its name as it was when the file was
  delivered), the run and chat it came from and when, and when it goes, all as tafs cloud lists them.
- `FilesStore`: the reads the screens need: every file, a file's bytes (for its preview, Download and Share) and Delete now.
  `StubFilesStore` holds the mockup's samples, none of them about money, until tafs cloud's store lands.
- `FilesGrouping`: the lists' order, in tafs cloud's facts: by day of the run in the account's zone, by taf and by run, the
  newest first, a run's files by name.
- `FilesModel`: the read, a failed read keeping nothing, the files of one taf or one run, and Delete now.

Tests: `FilesTests`.

## The screens (`ios/Tafs/Views/FilesViews.swift`)

- **Where it opens:** Account's Files row (beside People, Devices and Connect apps) opens the account's files. A taf's
  page has **one** way into that taf's, by tafs cloud's id: **Files, first on the header's line, "Files · Version 5 ·
  live"** (`TafFilesEntry`; the owner's test of 3054, 19 Nov, and the mockup's option A), in the page's ink, at least
  44 pt to tap like the version beside it, with a count when it has files kept now (VoiceOver: "Files, 3 files from its
  runs"). It shows with none yet too, so a taf's files are always found in the same place. The chat has no Files link of
  its own.
- **The list** (`FilesView`): the account's files with All, By taf and By run and a chip per taf, or one taf's by run; each
  row its type, name, taf and size, and when it goes ("goes today", "goes tomorrow", "goes in 12 days"). The days are
  under the run's day in the account's zone. With none, what lands there; a failed read says so.
- **A file** (`FileDetailView`): tafs cloud's start of the file (`preview`, its first 4 KB as plain text, control and
  direction characters taken out) drawn as text, never as Markdown or HTML, with a line when there's more. Only for a type with no text
  start (not CSV, MD, TXT or JSON) is the file drawn by the system from its bytes on the device; a text type whose read
  fails says it couldn't reach KIFF, one answered with no start shows no preview (as on Android), and neither reads any
  bytes. **Download** (saved where the person
  picks) and **Share** (the share sheet, with the file itself) read the bytes only when asked, the taf, the
  run, the type and size, "Everyone on this account", and when it goes; **Delete now** apart, behind a confirm that says
  it's gone for everyone, right away.
- **iPad, full width:** the list and the open file side by side; a file deleted there clears the side.
- **The copy on the device** (`FileCopy`): the bytes are written for the preview and the share sheet in their own folder
  under the app's temporary directory, with complete file protection, under a name kept to one path component, and
  removed when the file's screen closes. So nothing is kept on the device past it, and Delete now and the 30 days hold.
- **Under the chat** (`ChatFileChips`): a run's files as chips under its message, each opening the same file screen, with
  the date they're kept until. Drawn once tafs cloud says which run a chat message is: today's chat messages don't carry
  their run.

Words: `files.*`, in the 8 languages.

## tafs cloud's reads (`ios/Tafs/KIFF/TafsCloud+Files.swift`)

`CloudFilesStore` reads them, from each place Files opens:

- `GET /v1/files`, every page (`next`, then `?after=`), at most 10 at once: the account's files, newest first, each with
  its taf's name as tafs has it now, none once the taf is gone (`contracts/files-list.json`);
- `GET /v1/files/{id}`: the file's start as plain text, as tafs cloud cleaned it, for its preview
  (`contracts/files-file.json`);
- `GET /v1/files/{id}/download`: a URL to the file's bytes that lasts a minute, read at once and only for Download and
  the share sheet's file, and never handed out (`contracts/files-download.json`);
- `DELETE /v1/files/{id}`: Delete now, for everyone on the account.

A file's type is its name's extension, else its media type's; its run's time is when it was delivered; it goes when tafs
cloud says it expires. Tests: `FilesTests`, from the contracts' answers.

## Still to come

- The counts on Account's and the taf's rows, from the same read.
- The chips under a chat's run messages, from `?chat=<session>`.
- "Also sent to" is not in v1 (the contract has no field for it); it can come later as an optional `sent_to`.
