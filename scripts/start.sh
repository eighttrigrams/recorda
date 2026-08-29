#!/bin/bash
set -e

# recorda captures a screen and a microphone through AVFoundation, which exists
# only on a macOS host with a window server and a logged-in session. A container
# has neither, so this is not "degraded in the box" — it is structurally
# impossible there, the same way prober cannot run anywhere but the host because
# no sandbox holds the age key. Say so at the door rather than failing later
# inside ffmpeg with an I/O error that reads like a broken device.
if [ -f /.dockerenv ]; then
  echo "ERROR: recorda cannot run in a container — AVFoundation needs the host's"
  echo "window server and CoreAudio. Run this on the host."
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ERROR: ffmpeg is not on PATH. brew install ffmpeg"
  exit 1
fi

if [ ! -f config.edn ]; then
  echo "Creating default config.edn from template..."
  cp config.edn.template config.edn
fi

# Read the recordings directory out of the config rather than assuming it, and
# make it — nothing downstream creates it, and the first capture would die on a
# path that does not exist.
rec_dir=$(sed -n 's/.*:recordings-dir *"\([^"]*\)".*/\1/p' config.edn | head -1)
mkdir -p "${rec_dir:-recordings}" logs

echo host > .dev-server.lock

if [ ! -d node_modules ]; then
  echo "Installing npm dependencies..."
  npm install
fi

# SHADOW=false to skip hot reload and run a release build instead.
if [ "${SHADOW:-true}" = "true" ]; then
  echo "Starting shadow-cljs watch..."
  npx shadow-cljs watch app &
  echo $! > .shadow-cljs.pid
  for _ in $(seq 1 60); do
    if grep -q "shadow.cljs.devtools.client" resources/public/recorda/js/main.js 2>/dev/null; then
      break
    fi
    sleep 1
  done
else
  echo "Building ClojureScript..."
  npx shadow-cljs release app
fi

echo "Starting server in development mode..."
DEV=true clojure -X:run
