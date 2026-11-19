# Files

What the account's tafs deliver in their runs (a report, a sheet, a document), kept so the people on the account can open,
save and share them. From the owner-approved mockup v2.1. **Built behind a switch, off** (`FilesFeature.on`), until tafs
cloud's store and reads land; until then the iPhone and iPad read `StubFilesStore`, and nothing of it shows.

## The owner's rules

- **Kept 30 days from the run**, then gone on its own (`FilesFeature.keptDays`). tafs cloud decides when a file goes and says
  it (`goesAt`); the app only counts the days to that date (`FilesGrouping.daysLeft`), never below none.
- **Share sends the file itself** through the device's share sheet. There's no public link.
- **Everyone on the account** sees the account's files.
- **Delete now**, behind a confirm, deletes for everyone on the account, at once. The file leaves the list only once the
  store has deleted it (`FilesModel.delete`).
- **No tab of its own:** a Files row in Account, a Files row on each taf's page, and under the chat the files a run
  delivered, with a Files entry at the chat's top.

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

- **Where it opens:** Account's Files row (beside People, Devices and Connect apps) opens the account's files; the taf page's
  Files line, under its version, and the Files entry at the top of its chat open that taf's, by tafs cloud's id.
- **The list** (`FilesView`): the account's files with All, By taf and By run and a chip per taf, or one taf's by run; each
  row its type, name, taf and size, and when it goes ("goes today", "goes tomorrow", "goes in 12 days"). The days are
  under the run's day in the account's zone. With none, what lands there; a failed read says so.
- **A file** (`FileDetailView`): the system's preview of the file on the device (QuickLook; nothing goes to a viewer
  service), **Download** (saved where the person picks), **Share** (the share sheet, with the file itself), the taf, the
  run, the type and size, "Everyone on this account", and when it goes; **Delete now** apart, behind a confirm that says
  it's gone for everyone, right away.
- **iPad, full width:** the list and the open file side by side; a file deleted there clears the side.
- **Under the chat** (`ChatFileChips`): a run's files as chips under its message, each opening the same file screen, with
  the date they're kept until. Drawn once tafs cloud says which run a chat message is: today's chat messages don't carry
  their run.

Words: `files.*`, in the 8 languages.

## tafs cloud's reads (`ios/Tafs/KIFF/TafsCloud+Files.swift`)

`CloudFilesStore` reads them, from each place Files opens:

- `GET /v1/files`, every page (`next`, then `?after=`), at most 10 at once: the account's files, newest first, each with
  its taf's name as it was when the file was delivered (`contracts/files-list.json`);
- `GET /v1/files/{id}/download`: a URL to the file's bytes that lasts a minute, read at once for its preview, Download
  and the share sheet's file, and never handed out (`contracts/files-download.json`);
- `DELETE /v1/files/{id}`: Delete now, for everyone on the account.

A file's type is its name's extension, else its media type's; its run's time is when it was delivered; it goes when tafs
cloud says it expires. Tests: `FilesTests`, from the contracts' answers.

## Still to come

- The counts on Account's and the taf's rows, from the same read.
- The chips under a chat's run messages, from `?chat=<session>`.
- "Also sent to" is not in v1 (the contract has no field for it); it can come later as an optional `sent_to`.
