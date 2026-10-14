# AGENTS.md

For AI assistants working on tafs. Read this first.

- tafs is its own product. It uses KIFF the way any customer does: the public API
  (`api.kiff.dev`) and the gateway (`mcp.kiff.dev`). Never a back door into
  kiff-cloud. If tafs needs something KIFF lacks, open an issue on kiff/kiff-cloud.
- The phone never decides. In production KIFF decides every action; `CardCheck`
  only drives the sample mode and explains decisions offline.
- An approval counts only when KIFF verifies its signature against the device's
  key. Never add a path where a server vouches for an owner's answer.
- Voice: plain, short, specific. Tafs, Cards, held, approve once, refuse, revoke.
  Vibrant look, dark ground, one amber accent for "needs you".
- iOS only (SwiftUI, iOS 16+). No Android, no React Native.
- Jobs are few and consequential. Add a job only with evidence it is painful and
  high-stakes; tafs is not a catalogue of integrations.

## Working

```bash
cd ios && xcodegen generate
xcodebuild test -project Tafs.xcodeproj -scheme Tafs -destination 'platform=iOS Simulator,name=iPhone 14 Pro'
```

New behaviour gets tests. Feature branches only; never push to `main`.
