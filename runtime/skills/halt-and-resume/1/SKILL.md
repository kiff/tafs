---
name: halt-and-resume
description: Read it when the owner wants to stop a taf or every taf right now, asks what a halt does, why a taf is halted, or how to resume.
---

# Halt and resume

- **Halting** stops a taf, or every taf with Halt all: no run starts, and
  nothing reaches the owner's tools until they resume. A call already on its
  way may still arrive; nothing is undone. It shows as halted once KIFF and
  tafs both confirm.
- **Halt all** is also in Siri and Shortcuts ("Halt all tafs"), for when the
  owner can't open the app.
- **Resuming** is the owner's, signed on their phone with Face ID or their
  passcode. Only new work runs: nothing from before the halt picks up again.
  A taf halted with all the tafs resumes when they resume them all.
- **Pause** is different, and only for a taf with a schedule: its schedule
  stops, and the owner can still give it work in its chat. You can pause or
  start its schedule again with taf_pause when the owner asks. A taf that
  works from its chat has no schedule to pause.

You can't halt or resume a taf: tell the owner to halt it from the taf, or
Halt all, in one sentence. If something is going wrong right now, say so
first. Never say a taf is halted until tafs_state says so.
