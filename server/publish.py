#!/usr/bin/env python3
"""Privileged Firestore writer for the server crawler (phase 3).
Reads the planned writes produced by the Kotlin crawlers and commits them with a Google service account, which
bypasses the client security rules. Credentials come ONLY from the environment (GitHub Actions secret):
  FIREBASE_SA_JSON   full JSON key of the service account (role: Cloud Datastore User on project moasher-libya)
Safety: values are re-checked here with the same bounds as the rules; DRY_RUN=1 prints instead of writing.
Usage: python3 server/publish.py /tmp/board_writes.json /tmp/cache_seed.json /tmp/news_seed.json"""
import hashlib, json, os, sys, time

PROJECT = 'moasher-libya'
ROOT = f'projects/{PROJECT}/databases/(default)/documents'
BOUNDS = {'cur_usd': (2, 40), 'cur_eur': (2, 45), 'cur_gbp': (2, 55), 'cur_tnd': (0.05, 2), 'cur_egp': (0.5, 40),
          'chk_usd': (2, 40), 'chk_usd_tr': (2, 40), 'chk_eur_tr': (2, 45), 'chk_cards': (300, 20000),
          'gold_k18': (100, 8000), 'gold_k21': (100, 9000), 'gold_new': (100, 9000), 'gold_used': (100, 9000), 'gold_cast': (100, 9000),
          'silver_turk': (3, 300), 'silver_turk_chk': (3, 300), 'w_gold': (300, 50000), 'w_silver': (3, 2000), 'w_brent': (10, 400), 'w_wti': (10, 400)}

def val(v):
    if isinstance(v, bool): return {'booleanValue': v}
    if isinstance(v, int): return {'integerValue': str(v)}
    if isinstance(v, float): return {'doubleValue': v}
    return {'stringValue': str(v)}

def ok_value(k, v):
    lo, hi = BOUNDS.get(k, (0.0001, 1e8)); return isinstance(v, (int, float)) and lo <= v <= hi

def build_writes(board, cache, news=None):
    now = int(time.time()); w = []
    for k, d in (board or {}).get('latest', {}).items():
        if not ok_value(k, d.get('v')) or d.get('t', 0) > now + 120: print('skip latest', k); continue
        w.append({'update': {'name': f'{ROOT}/latest/{k}', 'fields': {f: val(x) for f, x in d.items()}}})
    for tk in (board or {}).get('ticks', []):
        if not ok_value(tk['k'], tk['v']): continue
        w.append({'update': {'name': f"{ROOT}/ticks/{tk['k']}_{tk['t']}", 'fields': {f: val(x) for f, x in tk.items()}},
                  'currentDocument': {'exists': False}})          # append-only: never overwrite a history point
    for path, f in (cache or {}).items():
        w.append({'update': {'name': f'{ROOT}/{path}', 'fields': {k: val(x) for k, x in f.items()}},
                  'updateTransforms': [{'fieldPath': 't', 'setToServerValue': 'REQUEST_TIME'}]})
    for path, f in ((news or {}).get('docs') or {}).items():           # cache/news + cache/news_{id} (same cache shape)
        if not (path == 'cache/news' or (path.startswith('cache/news_') and path[11:].isdigit())): continue
        w.append({'update': {'name': f'{ROOT}/{path}', 'fields': {k: val(x) for k, x in f.items()}},
                  'updateTransforms': [{'fieldPath': 't', 'setToServerValue': 'REQUEST_TIME'}]})
    for path in (news or {}).get('deletes') or []:                       # details that left the list
        if path.startswith('cache/news_') and path[11:].isdigit(): w.append({'delete': f'{ROOT}/{path}'})
    return w

def session():
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
    info = json.loads(os.environ['FIREBASE_SA_JSON'])
    creds = service_account.Credentials.from_service_account_info(info, scopes=['https://www.googleapis.com/auth/datastore'])
    return AuthorizedSession(creds)

def main():
    board = json.load(open(sys.argv[1])) if len(sys.argv) > 1 and os.path.exists(sys.argv[1]) else None
    cache = json.load(open(sys.argv[2])) if len(sys.argv) > 2 and os.path.exists(sys.argv[2]) else None
    news = json.load(open(sys.argv[3])) if len(sys.argv) > 3 and os.path.exists(sys.argv[3]) else None
    writes = build_writes(board, cache, news)
    n_news = sum(1 for x in writes if 'cache/news' in (x.get('update', {}).get('name') or x.get('delete', '')))
    print(f'writes planned: {len(writes)}')
    if os.environ.get('DRY_RUN') == '1' or not os.environ.get('FIREBASE_SA_JSON'):
        for x in writes[:80]: print('  DRY', 'delete' if 'delete' in x else '', (x.get('update', {}).get('name') or x['delete']).split('/documents/')[1])
        return
    # Loop mode: skip cache/news docs whose content did not change since this runner last wrote them (STATE_FILE),
    # so a 1-minute loop only spends Firestore writes on real changes.
    state_path = os.environ.get('STATE_FILE'); state = {}
    if state_path and os.path.exists(state_path):
        try: state = json.load(open(state_path))
        except Exception: state = {}
    def sig(x):
        u = x.get('update')
        if not u or 'updateTransforms' not in x: return None, None     # latest/ticks/deletes are already change-only
        f = dict(u['fields'])
        try:   # snapshots embed their build time (ms) in the JSON string 's'; ignore it so only real content counts
            o = json.loads(f['s']['stringValue'])
            if isinstance(o, dict):
                f['s'] = {k: v for k, v in o.items() if not (isinstance(v, int) and v > 10**12)}
        except Exception: pass
        return u['name'], hashlib.sha1(json.dumps(f, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    keep = []
    for x in writes:
        name, h = sig(x)
        prev = state.get(name) if name else None
        # unchanged and refreshed < 19 min ago: skip (clients treat cache docs older than 30 min as stale)
        if prev and isinstance(prev, list) and prev[0] == h and time.time() - prev[1] < 19 * 60: continue
        keep.append(x)
    if len(keep) != len(writes): print(f'unchanged skipped: {len(writes) - len(keep)}')
    writes = keep
    s = session(); failed = 0
    # ticks use exists:false preconditions, so commit one by one (a failed precondition must not drop the batch)
    for x in writes:
        r = s.post(f'https://firestore.googleapis.com/v1/{ROOT}:commit', json={'writes': [x]}, timeout=60)
        if not r.ok and 'ALREADY_EXISTS' not in r.text and 'FAILED_PRECONDITION' not in r.text:
            failed += 1; print('write failed', (x.get('update', {}).get('name') or x['delete']).split('/documents/')[1], r.status_code)
        else:
            name, h = sig(x)
            if name: state[name] = [h, time.time()]
    if state_path:
        try: json.dump(state, open(state_path, 'w'))
        except Exception: pass
    # heartbeat so the app owner (and a later rules switch) can verify the crawler is alive
    s.post(f'https://firestore.googleapis.com/v1/{ROOT}:commit', json={'writes': [{'update': {'name': f'{ROOT}/config/crawler', 'fields': {
        'writes': val(len(writes)), 'failed': val(failed), 'news': val(n_news)}}, 'updateTransforms': [{'fieldPath': 'at', 'setToServerValue': 'REQUEST_TIME'}]}]}, timeout=60)
    print(f'done: {len(writes) - failed} ok, {failed} failed')
    relay_ingest(board, cache)
    sys.exit(1 if failed else 0)


def relay_ingest(board, cache):
    """Push a public snapshot to the Cloudflare Worker (KV). Skipped when RELAY_INGEST_SECRET is unset."""
    secret = os.environ.get('RELAY_INGEST_SECRET')
    if not secret or os.environ.get("DRY_RUN") == "1":
        return
    url = os.environ.get('RELAY_INGEST_URL') or 'https://moasher-relay.moasher-libya.workers.dev/ingest'
    def item(k, d):
        t = d.get('t') or 0
        pt = d.get('pt') or 0
        return {'key': k, 'label': d.get('lbl') or k, 'unit': d.get('u') or '',
                'value': d.get('v'), 't': (t * 1000 if t and t < 10**12 else t),
                'pv': d.get('pv'), 'pt': (pt * 1000 if pt and pt < 10**12 else pt) or None}
    snap = {'at': int(time.time() * 1000), 'board': {}, 'posts': []}
    src = ((board or {}).get('states') or (board or {}).get('latest') or {})
    for k, d in src.items():
        if not ok_value(k, d.get('v')): continue
        snap['board'][k] = item(k, d)
    if snap['board']:
        snap['posts'] = [{'t': s['t'] or 0, 'id': 1000000 + i, 'l': [[s['key'], s['label'], s['value'], s['unit']]]}
                         for i, s in enumerate(snap['board'].values())]
    if cache:
        if cache.get('cache/materials'):
            snap['materials'] = cache['cache/materials']
        mkt = {k.split('/', 1)[1]: v for k, v in cache.items() if k.startswith('mkt/')}
        if mkt:
            snap['mkt'] = mkt
        if cache.get('cache/cbl'):
            snap['cbl'] = cache['cache/cbl']
    try:
        import requests as rq
        r = rq.post(url, json=snap, headers={'Authorization': 'Bearer ' + secret, 'User-Agent': 'Mozilla/5.0 (compatible; MoasherCrawler/1.0; +https://moasher-libya.web.app)'}, timeout=60)
        print('relay ingest:', r.status_code)
    except Exception as e:
        print('relay ingest failed:', type(e).__name__)

if __name__ == '__main__':
    main()
