# Files

What the account's tafs deliver in their runs (a report, a sheet, a document), kept so the people on the account can open,
save and share them. From the owner-approved mockup v2.1. **Built behind a switch, off** (`FilesFeature.on`), until tafs
cloud's store and reads land; until then the iPhone and iPad read `StubFilesStore`, and nothing of it shows.

## The owner's rules

- **Kept 30 days from the run**, then gone on its own (`FilesFeature.keptDays`). tafs cloud decides when a file goes and says
  it (`goesAt`); the app only counts the days to that date (`FilesGrouping.daysLeft`), never below none.
- **Share sends the file itself** through the device's share sheet. There's no public link.
- **Everyone on the account** sees the account's files.
- **Delete now**, behind a confirm, deletes for everyone on the account; a copy the taf sent elsewhere stays there. The file
  leaves the list only once the store has deleted it (`FilesModel.delete`).
- **No tab of its own:** a Files row in Account, a Files row on each taf's page, and under the chat the files a run
  delivered, with a Files entry at the chat's top.

## The model (`ios/Tafs/Model/Files.swift`)

- `DeliveredFile`: the file (name, type, size), the taf that made it, the run it came from and when, where else the run sent
  it, and when it goes, all as tafs cloud lists them.
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
  run, the type and size, where else it went, "Everyone on this account", and when it goes; **Delete now** apart, behind
  a confirm that says it's gone for everyone and that a copy sent elsewhere stays.
- **iPad, full width:** the list and the open file side by side; a file deleted there clears the side.
- **Under the chat** (`ChatFileChips`): a run's files as chips under its message, each opening the same file screen, with
  the date they're kept until. Drawn once tafs cloud says which run a chat message is: today's chat messages don't carry
  their run.

Words: `files.*`, in the 8 languages.

## Still to come

- tafs cloud's reads in place of the stub, once its contract is set (the account's files, by taf, by run, by chat, a
  file's bytes and Delete now).
- A chat message's run, for the chips under it.
- The counts on Account's and the taf's rows, from the same read.
