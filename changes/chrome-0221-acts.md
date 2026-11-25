- **Chrome 0.2.21: a taf's click that ran is never reported as failed because its page grew too large to send.** It's reported as done, and the taf reads the page again, so it never clicks Submit twice. Only opening or reading a page that's too large is refused ([docs/chrome.md](../docs/chrome.md)).
- Chrome end-to-end tests cover:
  - Coach's own send starting a run in your tab;
  - Coach's read of a page with frames;
  - a page too large to send even when cut.

  Two tests of a page's dialogs as it loads now accept either way the race goes.
