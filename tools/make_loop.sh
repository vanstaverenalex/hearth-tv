#!/usr/bin/env bash
# Turn a clip into a slow, seamless 4K loop.
#   make_loop.sh <input.mp4> <output.mp4> [max_seconds=40] [fade_seconds=2.5] [speed=0.5]
#
# Speed: the footage plays at [speed] (0.5 = half speed), so scenes drift calmly like a
# screensaver. 50/60 fps sources slow down cleanly to 30 fps; slower sources get in-between
# frames blended so the motion stays smooth.
#
# Loop: take L seconds of (slowed) footage, then overlay its first F seconds, fading in, on top
# of its last F seconds. The output ends on exactly the frame it starts with, so there's no cut.
set -euo pipefail
IN="$1"; OUT="$2"; MAX="${3:-40}"; F="${4:-2.5}"; SPEED="${5:-0.5}"

DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$IN")
FPS=$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$IN" | head -1 | tr -d ", ")
SRCFPS=$(python3 -c "from fractions import Fraction as F; print(float(F('$FPS')))")
# Frames per second after slowing down; >= 30 means real frames all the way, no blending.
RETIME=$(python3 -c "
s=float('$SRCFPS')*float('$SPEED')
print('fps=30' if s >= 29.5 else 'minterpolate=fps=30:mi_mode=blend')")
# Lengths in OUTPUT time (source seconds / speed). Skip the first source second (often an edit-in).
read SKIP L F INLEN <<< $(python3 -c "
sp=float('$SPEED'); d=(float('$DUR')-0.15); m=float('$MAX'); f=float('$F')
s = 1.0 if d >= 11 else 0.0
avail=(d-s)/sp
l=min(m, avail)
f=min(f, l/4)
print(f'{s:.3f} {l:.3f} {f:.3f} {l*sp+0.2:.3f}')")
SHIFT=$(python3 -c "print(f'{$L-2*$F:.3f}')")

echo "in=$IN dur=$DUR fps=$FPS speed=$SPEED -> ${L}s loop, fade ${F}s, retime: $RETIME"

ffmpeg -nostdin -hide_banner -loglevel error -y -ss "$SKIP" -t "$INLEN" -i "$IN" -filter_complex "\
[0:v]setpts=PTS/$SPEED,$RETIME,scale=3840:2160:force_original_aspect_ratio=increase:flags=lanczos,crop=3840:2160,setsar=1,format=yuv420p,trim=duration=$L,setpts=PTS-STARTPTS,split[a][b];\
[a]trim=start=$F,setpts=PTS-STARTPTS[main];\
[b]trim=end=$F,setpts=PTS-STARTPTS,format=yuva420p,fade=t=in:st=0:d=$F:alpha=1,setpts=PTS+$SHIFT/TB[head];\
[main][head]overlay=shortest=1:format=auto,format=yuv420p[v]" \
  -map "[v]" -an -r 30 \
  -c:v libx264 -preset veryfast -crf 19 -maxrate 30M -bufsize 60M \
  -profile:v high -level:v 5.1 -pix_fmt yuv420p -g 60 -keyint_min 60 \
  -movflags +faststart "$OUT"

ffprobe -v error -show_entries format=duration,size -of csv=p=0 "$OUT"
