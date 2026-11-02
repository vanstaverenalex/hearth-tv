"""Mirror 24h of Gracenote listings for the channels in channels.json into one small JSON for the TVs."""
import json, time, urllib.request
cfg = json.load(open('channels.json'))
g = cfg['gracenote']
want = {cs for c in cfg['channels'] for cs in c.get('gn', [])}
start = int(time.time()) // 1800 * 1800 - 1800
UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
      'Accept': 'application/json, text/plain, */*', 'Accept-Language': 'en-US,en;q=0.9',
      'Referer': 'https://tvlistings.gracenote.com/grid-affiliates.html?aid=orbebb'}
chans = {}
for k in range(4):
    url = (f"https://tvlistings.gracenote.com/api/grid?lineupId={g['lineupId']}&timespan=6&headendId={g['headendId']}"
           f"&country=USA&timezone=&device={g.get('device','X')}&postalCode={g['postalCode']}&isOverride=true"
           f"&time={start + k * 6 * 3600}&pref=16%2C128&userId=-&aid=orbebb&languagecode=en-us")
    for attempt in range(3):
        try:
            data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30)); break
        except Exception as e:
            print('window', k, 'attempt', attempt, e); time.sleep(5)
    else:
        continue
    for c in data.get('channels', []):
        cs = c.get('callSign')
        if cs not in want: continue
        ch = chans.setdefault(cs, {'callSign': cs, 'events': {}})
        for e in c.get('events', []):
            p = e.get('program') or {}
            ch['events'][e['startTime']] = {
                'startTime': e['startTime'], 'endTime': e['endTime'], 'flag': e.get('flag') or [], 'thumbnail': e.get('thumbnail'),
                'program': {k2: p.get(k2) for k2 in ('title', 'episodeTitle', 'season', 'episode', 'shortDesc')},
            }
    time.sleep(2)
out = {'updated': int(time.time()), 'channels': [
    {'callSign': cs, 'events': sorted(v['events'].values(), key=lambda e: e['startTime'])} for cs, v in sorted(chans.items())]}
n = sum(len(c['events']) for c in out['channels'])
print(f"{len(out['channels'])} of {len(want)} call signs, {n} listings")
missing = sorted(want - set(chans))
if missing: print('no data for:', ' '.join(missing))
if n == 0: raise SystemExit('no listings')
json.dump(out, open('gracenote.json', 'w'), separators=(',', ':'))
