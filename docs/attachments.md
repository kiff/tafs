# Photos and files on a message

A person can attach photos and files to a message, in a taf's chat and in Coach mode.
The model reads them with the message. They are the person's own: only they can open
them again.

Code: in the app, `Views/AttachViews.swift` (the paperclip, the tray, the files on a
sent message), `Services/Attachments.swift` (`FilePrep`, `PendingFile`), and the
Attachments section of `AppModel`; `TafsCloud.attach`, `upload` and `attachment`. In
tafs cloud, `attachFile`, `readFile` and `files` in `handler.ts`. kiff/agents keeps the
files and checks them again.

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
black. Only its pixels are kept, so it carries no location or other metadata.

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

## Opening a file again

A sent message shows its files; each opens full size in Quick Look. The app asks tafs
cloud for a download link (`GET /v1/tafs/{id}/attachments/{att}`), which works for a
minute, only for the person who attached it. The copy goes into the app's temporary
folder (`tmp/attachments`) and is removed on sign-out and on an account switch.

## What's kept

kiff/agents keeps each file as long as its message: 30 days from when it was sent, or
until the person deletes the chat, the taf is removed or the account is deleted. An
upload no message named goes after an hour. The model sees a message's files inline with
the request, never in a model provider's file storage. Never kept for the taf or in its
notes. [data.md](data.md) has the details.
