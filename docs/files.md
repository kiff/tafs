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

## Still to come

The screens (Account's row, the taf's row, the files under the chat, a file with its preview, Download, Share and Delete
now, and the iPad's list beside the open file), with the `files.*` words in the 8 languages; then tafs cloud's reads in place
of the stub, once its contract is set.
