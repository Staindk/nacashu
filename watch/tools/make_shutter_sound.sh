#!/bin/sh
# Makes the watch's shutter sound from Android's camera click (AOSP, Apache License 2.0; see
# resources/sounds/NOTICE). The watch plays raw signed 8-bit mono PCM at 16 kHz.
# Run from watch/. Needs curl and ffmpeg.
set -e
AOSP=https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/1cdfff555f4a21f71ccc978290e2e212e2f8b168
tmp=$(mktemp -d)
curl -sSfL -o "$tmp/camera_click.ogg" "$AOSP/data/sounds/effects/ogg/camera_click.ogg"
# The last 55 ms of the file are silence.
ffmpeg -v error -y -i "$tmp/camera_click.ogg" -af atrim=0:0.363 -ac 1 -ar 16000 -f s8 resources/sounds/shutter.raw
rm -r "$tmp"
