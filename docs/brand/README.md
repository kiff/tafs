# The tafs icon

- `tafs-icon-1024.png`: the app icon, 1024×1024, full bleed, no transparency. iOS
  and the App Store round its corners; the character sits in the middle 76% so
  nothing is clipped. The same file is `ios/Tafs/Assets.xcassets/AppIcon.appiconset/AppIcon.png`.
- `tafs-icon-source.png`: the artwork it is made from (1254×1254).
- `tafs-icon-transparent-1024.png`: the character alone on a transparent 1024×1024 square, centred with a 12 px margin
  all round, for where an app draws it on a surface it doesn't own: Chrome's toolbar (`chrome/icons/icon-*.png`), an
  email's header and a web page (kiff/cloud's `tafs-icon-512.png`), light or dark. **There is no vector or transparent
  master of the character** (`tafs-icon-source.png` is the artwork on a flat cream, and the app icon is full bleed on
  purpose), so this one is made from the source by `scripts/brand_transparent.py`: the cream the border reaches is the
  background, its edge is unmixed from the cream (difference matting, so no cream fringe), and the white face, which the
  border can't reach, is kept. The same source gives the same file byte for byte; the Chrome icons and the 512 px copy
  are this file resized with premultiplied alpha. If a designer sends a real transparent master, it replaces this file
  and the script goes.
- `tafs-icon.svg`, `tafs-logo-light.svg` and `tafs-logo-dark.svg` are the earlier mark (a "t" that is also a worker, amber
  and ink) and are not what the apps show now.
