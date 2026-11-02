#!/usr/bin/env bash
# Turn a clip into a seamless 4K loop.
#   make_loop.sh <input.mp4> <output.mp4> [max_seconds=32] [fade_seconds=2]
#
# How: take L seconds of the clip (skipping the first second, which is often an
# edit-in), then overlay the first F seconds, fading in, on top of the last F seconds.
# The output ends on exactly the frame it starts with, so the loop has no visible cut.
set -euo pipefail
IN="$1"; OUT="$2"; MAX="${3:-32}"; F="${4:-2}"

DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$IN")
FPS=$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$IN")
FPSN=$(python3 -c "from fractions import Fraction as F; f=float(F('$FPS')); print(30 if f>30.5 else round(f,3))")
# Use only footage that really exists: skip the first second when there's room,
# and shorten the blend for very short clips so the loop math always holds.
read SKIP L F <<< $(python3 -c "
d=float('$DUR')-0.15; m=float('$MAX'); f=float('$F')
if d >= m+1: s,l = 1.0, m
elif d >= 10: s,l = 1.0, d-1
else: s,l = 0.0, d
f = min(f, l/4)
print(f'{s:.3f} {l:.3f} {f:.3f}')")
SHIFT=$(python3 -c "print(f'{$L-2*$F:.3f}')")
GOP=$(python3 -c "print(int(round($FPSN*2)))")

echo "in=$IN dur=$DUR fps=$FPS -> using ${L}s, fade ${F}s, out fps $FPSN"

ffmpeg -nostdin -hide_banner -loglevel error -y -ss "$SKIP" -t "$L" -i "$IN" -filter_complex "\
[0:v]fps=$FPSN,scale=3840:2160:force_original_aspect_ratio=increase:flags=lanczos,crop=3840:2160,setsar=1,format=yuv420p,split[a][b];\
[a]trim=start=$F,setpts=PTS-STARTPTS[main];\
[b]trim=end=$F,setpts=PTS-STARTPTS,format=yuva420p,fade=t=in:st=0:d=$F:alpha=1,setpts=PTS+$SHIFT/TB[head];\
[main][head]overlay=shortest=1:format=auto,format=yuv420p[v]" \
  -map "[v]" -an \
  -c:v libx264 -preset veryfast -crf 20 -maxrate 28M -bufsize 56M \
  -profile:v high -level:v 5.1 -pix_fmt yuv420p -g "$GOP" -keyint_min "$GOP" \
  -movflags +faststart "$OUT"

ffprobe -v error -show_entries format=duration,size -of csv=p=0 "$OUT"
