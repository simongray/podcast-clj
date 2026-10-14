#!/usr/bin/env bash
# Run a device test of podcast-clj from this checkout:
#
#   dev/devices/run.sh electron
#   dev/devices/run.sh capacitor   # needs Xcode and an iOS simulator
#   dev/devices/run.sh expo        # needs Xcode, an iOS simulator and CocoaPods
#
# It builds the test app, runs it against the local server of server.mjs,
# prints the result of each check, and exits with 1 when one fails. Set
# SIMULATOR to the name or UDID of the iOS simulator to use, or it takes the
# booted iPhone, or else the first iPhone.
set -euo pipefail

platform=${1:?"name a test: electron, capacitor or expo"}
here=$(cd "$(dirname "$0")" && pwd)
dir=$here/$platform
results=$dir/results.json
[ -d "$dir" ] || { echo "There's no test for $platform" >&2; exit 2; }

cd "$dir"
rm -f "$results"
[ -d node_modules ] || npm install --no-audit --no-fund

node "$here/server.mjs" "$results" 8771 > server.log 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT

# The UDID of the simulator to use, booted.
simulator() {
  local udid
  udid=$(xcrun simctl list devices available -j | node -e '
    const all = Object.values(JSON.parse(require("fs").readFileSync(0)).devices).flat();
    const want = process.env.SIMULATOR;
    const iphone = d => d.name.startsWith("iPhone");
    const d = want ? all.find(d => d.name === want || d.udid === want)
                   : all.find(d => iphone(d) && d.state === "Booted") || all.find(iphone);
    if (!d) { console.error("No simulator found; install one in Xcode"); process.exit(1); }
    console.log(d.udid);')
  xcrun simctl bootstatus "$udid" -b > /dev/null
  echo "$udid"
}

# Build the Xcode project or workspace given in `$@` for the simulator
# `$udid`, then install and launch the app `$app` with the bundle id `$id`.
run_ios() {
  LANG=en_US.UTF-8 xcodebuild "$@" -sdk iphonesimulator \
    -destination "id=$udid" -derivedDataPath build -quiet build
  xcrun simctl install "$udid" "$app"
  xcrun simctl launch --terminate-running-process "$udid" "$id" > /dev/null
}

case $platform in
  electron)
    clojure -M:cljs release main renderer
    npx electron .
    ;;
  capacitor)
    clojure -M:cljs release page
    if [ ! -d ios ]; then
      npx cap add ios
      # plain HTTP to the local server
      /usr/libexec/PlistBuddy \
        -c "Add :NSAppTransportSecurity dict" \
        -c "Add :NSAppTransportSecurity:NSAllowsLocalNetworking bool true" \
        ios/App/App/Info.plist
    fi
    npx cap copy ios
    udid=$(simulator)
    app=build/Build/Products/Debug-iphonesimulator/App.app
    id=dk.simongray.probe
    run_ios -project ios/App/App.xcodeproj -scheme App -configuration Debug
    ;;
  expo)
    clojure -M:cljs release app
    if [ ! -d ios ]; then
      npx expo prebuild --platform ios --no-install
      (cd ios && LANG=en_US.UTF-8 pod install)
    fi
    udid=$(simulator)
    workspace=$(ls -d ios/*.xcworkspace)
    scheme=$(basename "$workspace" .xcworkspace)
    # Release, so that the app holds its JavaScript and needs no Metro
    app=build/Build/Products/Release-iphonesimulator/$scheme.app
    id=dk.simongray.probe.expo
    run_ios -workspace "$workspace" -scheme "$scheme" -configuration Release
    ;;
  *)
    echo "There's no test for $platform" >&2
    exit 2
    ;;
esac

for _ in $(seq 180); do
  [ -s "$results" ] && break
  sleep 1
done
node "$here/report.mjs" "$results"
