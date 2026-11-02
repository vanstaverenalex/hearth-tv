"""Download Pexels candidates, measure them, and draw a review sheet (3 frames + stats per clip)."""
import json, os, subprocess, sys
from PIL import Image, ImageDraw
rows, meta = [], {}
for line in open('tools/candidates.txt'):
    line = line.strip()
    if not line or line.startswith('#'): continue
    vid, name = line.split()[:2]
    out = f'work/{vid}.mp4'
    r = subprocess.run(['curl', '-sSL', '--retry', '2', '-A', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
                        '-o', out, '-w', '%{http_code} %{url_effective}', f'https://www.pexels.com/download/video/{vid}/'], capture_output=True, text=True)
    code, url = (r.stdout.split(' ', 1) + [''])[:2]
    if code != '200' or os.path.getsize(out) < 100000:
        print('FAIL', vid, code, url[:120]); meta[vid] = {'name': name, 'error': code}; continue
    p = json.loads(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height,r_frame_rate:format=duration',
                                   '-of', 'json', out], capture_output=True, text=True).stdout)
    st = p['streams'][0]; dur = float(p['format']['duration']); n, d = st['r_frame_rate'].split('/'); fps = float(n) / float(d)
    frames = []
    for i, f in enumerate([0.15, 0.5, 0.85]):
        fn = f'work/{vid}_{i}.png'
        subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-ss', str(dur * f), '-i', out, '-frames:v', '1', '-vf', 'scale=384:216', fn])
        frames.append(fn)
    # motion: mean abs difference between frames 1s apart (time-lapses / shaky clips score high)
    a, b = 'work/m0.png', 'work/m1.png'
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-ss', str(dur * 0.5), '-i', out, '-frames:v', '1', '-vf', 'scale=160:90,format=gray', a])
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-y', '-ss', str(dur * 0.5 + 1), '-i', out, '-frames:v', '1', '-vf', 'scale=160:90,format=gray', b])
    try:
        A, B = list(Image.open(a).getdata()), list(Image.open(b).getdata())
        motion = sum(abs(x - y) for x, y in zip(A, B)) / len(A)
    except Exception: motion = -1
    meta[vid] = {'name': name, 'url': url, 'w': st['width'], 'h': st['height'], 'fps': round(fps, 2), 'dur': round(dur, 1), 'motion': round(motion, 1)}
    rows.append((vid, frames, meta[vid]))
    print(vid, meta[vid])
    os.remove(out)
json.dump(meta, open('work/candidates.json', 'w'), indent=1)
per = 10
for s in range(0, len(rows), per):
    chunk = rows[s:s + per]
    sheet = Image.new('RGB', (384 * 3, len(chunk) * (216 + 24)), 'white'); d = ImageDraw.Draw(sheet)
    for i, (vid, frames, m) in enumerate(chunk):
        y = i * 240
        for k, fn in enumerate(frames):
            try: sheet.paste(Image.open(fn).convert('RGB'), (k * 384, y))
            except Exception: pass
        d.text((6, y + 220), f"{vid} {m['name']}  {m['w']}x{m['h']} {m['fps']}fps {m['dur']}s  motion {m['motion']}", fill='black')
    sheet.save(f'work/sheet{s // per}.jpg', quality=85)
