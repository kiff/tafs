// Makes Mac/Assets.xcassets/MacAppIcon from the iPhone icon: swift Mac/make-icon.swift Tafs/Assets.xcassets/AppIcon.appiconset/AppIcon.png out-1024.png, then sips -z to each size.
import AppKit

// The macOS icon: the artwork in a rounded square of 824 on a 1024 canvas (corner 185),
// a transparent margin and a soft shadow, as macOS's own icons are drawn.
let args = CommandLine.arguments
let src = NSImage(contentsOfFile: args[1])!
let out = args[2]
let canvas: CGFloat = 1024, side: CGFloat = 824, radius: CGFloat = 185
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 4,
                           hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor.clear.set()
NSRect(x: 0, y: 0, width: canvas, height: canvas).fill()
let rect = NSRect(x: (canvas - side) / 2, y: (canvas - side) / 2 + 10, width: side, height: side)
let path = NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
// The shadow, under the tile.
NSGraphicsContext.saveGraphicsState()
let shadow = NSShadow()
shadow.shadowColor = NSColor.black.withAlphaComponent(0.30)
shadow.shadowOffset = NSSize(width: 0, height: -10)
shadow.shadowBlurRadius = 24
shadow.set()
NSColor.white.set()
path.fill()
NSGraphicsContext.restoreGraphicsState()
// The artwork, clipped to the tile.
NSGraphicsContext.saveGraphicsState()
path.addClip()
src.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.restoreGraphicsState()
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
