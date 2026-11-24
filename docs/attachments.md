# Photos and files on a message

A person can attach photos and files to a message, in a taf's chat and in Coach mode.
The model reads them with the message. They are the person's own: only they can open
them again.

Code: in the app, `Views/AttachViews.swift` (the paperclip, the tray, the files on a
sent message), `Services/Attachments.swift` (`FilePrep`, `PendingFile`), and the
Attachments section of `AppModel`; `TafsCloud.attach`, `upload` and `attachment`. In
tafs cloud, `attachFile`, `readFile` and `files` in `handler.ts`. kiff/agents keeps the
files and checks them again.

On Android: `views/AttachViews.kt` (the paperclip, `FileTray`, `MessageFiles`),
`model/Attachments.kt` (`FilePrep`, `PendingFile`), `files/PhotoPrep.kt` (redrawing a
photo), the Photos and files section of `AppModel`, and `TafsCloud.attach`, `upload` and
`attachment`. It works as below, with the differences noted.

In the Chrome extension's side panel (from 0.2.10): `chrome/src/attach.js` and the composer's tray in
`sidepanel/panel.js`, with the same types, limits and redraw, Chrome's file picker only, and no reopening a file
([chrome.md](chrome.md#files-on-a-message)).

## What can be attached

At most 5 files a message (`FilePrep`):

| Kind | Types | At most |
|---|---|---|
| Images | JPEG, PNG, GIF, WebP | 10 MB |
| PDF | | 25 MB |
| Text | plain text, CSV, Markdown | 2 MB |
| Office | Word (`.docx`), Excel (`.xlsx`) | 2 MB |

The app checks these before uploading, so a file over its limit is refused in the tray
with why, and never goes up. kiff/agents checks the same types and limits.

**Every image is redrawn as a JPEG** before it goes, whether from the camera, Photos or
Files: its long edge at most 2,576 pixels, drawn on white so a transparent PNG isn't
black. Only its pixels are kept, so it carries no location or other metadata. On Android,
`ImageDecoder` reads it (HEIC included) turned the way it was taken.

## Attaching

The paperclip offers **Take a photo**, **Choose photos** and **Choose a file**. Each file
goes up as soon as it's picked (`AppModel.attach`):

1. The app declares it to tafs cloud (`POST /v1/tafs/{id}/attachments` with
   `{media_type, name, size, sha256}`). kiff/agents checks its type and size, keeps a
   record of it for this person alone, and answers where to upload its bytes.
2. The phone uploads the bytes straight to storage, through a link that works for
   minutes. They never pass through tafs cloud.
3. The file waits in the tray above the message box: going up, ready, or why it can't
   go. Each has a remove control.

**Sending.** A message may be words, files, or both
(`{text, attachments: [ids]}` on the message routes).

- Send waits while any file is still going up, so a message never leaves a file just
  attached behind.
- Files go with a message of their own, never into a run at work: while the taf or
  Coach works, Send waits.
- Files that failed stay in the tray; the message takes only the ready ones.
- **Send again** on a sent message sends its words only.
- **An improvement's first message carries no files:** tafs cloud adds the taf's context to it. While Coach
  improves a taf, the paperclip isn't offered. Files picked earlier in a plain chat stay in the tray: on the
  iPhone, iPad and Mac the words go alone, the files wait for the next message (the chat then goes on as any
  other), and the composer says "Files go with your next message: send this one first, then attach them."
  (`coach.improve_no_files`, `AppModel.improvementHoldsFiles`). Before, Send put the words and the files back
  with nothing said.

## Opening a file again

A sent message shows its files; each opens full size in Quick Look. The app asks tafs
cloud for a download link (`GET /v1/tafs/{id}/attachments/{att}`), which works for a
minute, only for the person who attached it. The copy goes into the app's temporary
folder (`tmp/attachments/<id>`, under its own name only) and is removed on sign-out and on an account switch. As on
Android, the id keeps only its ASCII letters, digits, `_` and `-` (`AppModel.openedFolder`), and a name that is only a path
step (`.`, `..`) is `file` (`FileText.name`), so neither reaches outside that folder.

On Android a sent file opens in the phone's own app for its kind (Photos, a PDF viewer),
handed over read-only through the app's FileProvider. The copy goes into the app's cache
(`cache/attachments/<id>`, under its own name only) and is removed on sign-out. The id keeps only its letters,
digits, `_` and `-`, and a name that is only a path step (`.`, `..`) is `file`, so neither reaches outside that folder. The
paperclip's photos come from the system photo picker, which gives the app only the
photos the person picks, so the app asks for no photo permission; a chosen photo is
named "Photo 1", as on iOS. A photo from the camera is written to the app's cache, and
that copy is removed once it's redrawn.

## What's kept

kiff/agents keeps each file as long as its message: 30 days from when it was sent, or
until the person deletes the chat, the taf is removed or the account is deleted. An
upload no message named goes after an hour. The model sees a message's files inline with
the request, never in a model provider's file storage. Never kept for the taf or in its
notes. [data.md](data.md) has the details.
