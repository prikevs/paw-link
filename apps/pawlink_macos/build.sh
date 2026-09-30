#!/bin/zsh
set -eu
cd "${0:A:h}"
APP="$PWD/PawLink Pet.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
for name in eat-sheet-v1 jump-sheet-v1 groom-sheet-v1 wash-large-v5 roll-photo-v3 walk-sheet-v1 sleep-v1; do
  cp "assets/$name.png" "$APP/Contents/Resources/"
done
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>local.pawlink.desktop-pet</string>
<key>CFBundleName</key><string>PawLink Pet</string>
<key>CFBundleExecutable</key><string>PawLinkPet</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>0.1.0</string>
<key>LSUIElement</key><true/>
<key>NSHighResolutionCapable</key><true/>
<key>LSMinimumSystemVersion</key><string>13.0</string>
</dict></plist>
PLIST
mkdir -p .build/module-cache
cp PawLink.swift .build/main.swift
swiftc -O -target "$(uname -m)-apple-macosx13.0" -module-cache-path "$PWD/.build/module-cache" .build/main.swift API.swift -o "$APP/Contents/MacOS/PawLinkPet" -framework Cocoa -framework Network
codesign --force --sign - "$APP"
printf '%s\n' "$APP"
