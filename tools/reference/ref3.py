"""Reference parser v3 (spec for the Kotlin port)."""
import numpy as np
from PIL import Image

BAND_TOP, BAND_BOT = 0.19, 0.87


def strip_overlay(a):
    """test-only: remove our own green marker dots from user screenshots"""
    R, G, B = a[..., 0], a[..., 1], a[..., 2]
    disc = (G > R + 35) & (G > B + 20)
    dm = disc.copy()
    for sh in (1, 2, 3, 4):
        dm[sh:] |= disc[:-sh]; dm[:-sh] |= disc[sh:]
        dm[:, sh:] |= disc[:, :-sh]; dm[:, :-sh] |= disc[:, sh:]
    return dm


def load(path, strip=True):
    im = Image.open(path).convert("RGB")
    a = np.asarray(im).astype(np.int32)
    H, W, _ = a.shape
    bg = a[H // 2, 8]
    d = np.abs(a - bg).sum(axis=2)
    rb = a[..., 0] - a[..., 2]
    ink = (d > 165) | ((rb >= 78) & (d >= 60))
    struct = (d >= 60) | ((rb >= 78) & (d >= 45))   # ink + faint dots
    if strip:
        dm = strip_overlay(a)
        ink &= ~dm
        struct &= ~dm
    return im, ink, struct, W, H


# ---------------------------------------------------------------- calibration
def _highpass(v, win=25):
    """subtract a running median so only lattice peaks remain (half-pitch can no longer win)"""
    n = len(v)
    pad = np.pad(v, (win // 2, win // 2), mode="edge")
    windows = np.lib.stride_tricks.sliding_window_view(pad, win)
    base = np.median(windows, axis=1)
    return np.clip(v - base, 0, None)


def _proj(mask, W, H):
    y0, y1 = int(H * BAND_TOP), int(H * BAND_BOT)
    sub = mask[y0:y1]
    col = _highpass(sub.sum(axis=0).astype(float))
    r = np.zeros(H)
    r[y0:y1] = sub.sum(axis=1)
    row = _highpass(r)
    row[:y0] = 0
    row[y1:] = 0
    return col, row


def _score(proj, p, offs):
    n = len(proj)
    k = int((n - 2) / p) + 1
    pos = offs[:, None] + p * np.arange(k)[None, :]
    idx = np.clip(np.rint(pos).astype(int), 1, n - 2)
    v = np.maximum(np.maximum(proj[idx - 1], proj[idx]), proj[idx + 1])
    v = np.where(pos < n - 1, v, 0)
    s = v.sum(axis=1)
    j = int(s.argmax())
    return s[j], offs[j]


def _refine(proj, p0, o0):
    n = len(proj)
    ks, cs = [], []
    k = 0
    while o0 + k * p0 < n - 6:
        xc = o0 + k * p0
        if xc >= 6:
            lo, hi = int(xc - 5), min(int(xc + 5) + 1, n)
            win = proj[lo:hi]
            if win.max() > 8:
                w = win - win.min()
                if w.sum() > 0:
                    ks.append(k)
                    cs.append((w * np.arange(lo, hi)).sum() / w.sum())
        k += 1
    if len(ks) < 4:
        return None
    A = np.vstack([ks, np.ones(len(ks))]).T
    (p, o), *_ = np.linalg.lstsq(A, np.array(cs), rcond=None)
    if abs(p - p0) > p0 * 0.05:
        return None
    return p, o


def calibrate(struct, W, H):
    col, row = _proj(struct, W, H)
    pitches = np.arange(18.0, 100.0, 0.1)
    sc = []
    for p in pitches:
        offs = np.arange(0, p, 0.25)
        sc.append(_score(col, p, offs)[0] + _score(row, p, offs)[0])
    sc = np.array(sc)
    mx = sc.max()
    if mx <= 0:
        return None
    good = np.nonzero(sc >= 0.93 * mx)[0]
    # take the first run of near-max pitches then the largest in it (skips sub-multiples)
    pc = pitches[good.max()]
    # fine
    best = (-1, pc, 0, 0)
    for p in np.arange(pc - 0.6, pc + 0.6, 0.02):
        offs = np.arange(0, p, 0.25)
        sx = _score(col, p, offs)
        sy = _score(row, p, offs)
        if sx[0] + sy[0] > best[0]:
            best = (sx[0] + sy[0], p, sx[1], sy[1])
    _, p, xo, yo = best
    rx = _refine(col, p, xo)
    ry = _refine(row, p, yo)
    return dict(x0=rx[1] if rx else xo, y0=ry[1] if ry else yo,
                px=rx[0] if rx else p, py=ry[0] if ry else p, coarse=pc)


# ---------------------------------------------------------------- parse
DIRS = {(1, 0), (-1, 0), (0, 1), (0, -1)}


def head_score(ink, W, H, x, y, dx, dy):
    """ink pixels beside the line in a tight window around the endpoint.
    An arrowhead is a filled triangle spanning u = -12..+11 around the grid point, so it fills
    the side area; a tail end has nothing there. Window stays within one grid step so the other
    end of a one-segment arrow cannot leak in."""
    qx, qy = -dy, dx
    cnt = 0
    for u in range(-9, 10):
        for s in (-12, -11, -10, -9, -8, -7, -6, -5, 5, 6, 7, 8, 9, 10, 11, 12):
            sx = int(round(x + dx * u + qx * s))
            sy = int(round(y + dy * u + qy * s))
            if 0 <= sx < W and 0 <= sy < H and ink[sy, sx]:
                cnt += 1
    return cnt


def parse(ink, W, H, cal):
    y0, y1 = int(H * BAND_TOP), int(H * BAND_BOT)
    cmin = int(np.ceil((0.03 * W - cal["x0"]) / cal["px"]))
    cmax = int(np.floor((0.97 * W - cal["x0"]) / cal["px"]))
    rmin = int(np.ceil((y0 - cal["y0"]) / cal["py"]))
    rmax = int(np.floor((y1 - cal["y0"]) / cal["py"]))
    NC, NR = cmax - cmin + 1, rmax - rmin + 1
    px = lambda c: cal["x0"] + (cmin + c) * cal["px"]
    py = lambda r: cal["y0"] + (rmin + r) * cal["py"]

    def cover(xa, ya, xb, yb):
        hit = 0
        for t in np.linspace(0.25, 0.75, 9):
            x = int(round(xa + (xb - xa) * t)); y = int(round(ya + (yb - ya) * t))
            if ink[max(y - 1, 0):y + 2, max(x - 1, 0):x + 2].any():
                hit += 1
        return hit

    adj = {}
    for r in range(NR):
        for c in range(NC):
            for dc, dr in ((1, 0), (0, 1)):
                c2, r2 = c + dc, r + dr
                if c2 < NC and r2 < NR and cover(px(c), py(r), px(c2), py(r2)) >= 8:
                    adj.setdefault((c, r), set()).add((c2, r2))
                    adj.setdefault((c2, r2), set()).add((c, r))
    seen, arrows, blockers = set(), [], []
    for p in adj:
        if p in seen:
            continue
        st, comp = [p], []
        seen.add(p)
        while st:
            q = st.pop(); comp.append(q)
            for n in adj[q]:
                if n not in seen:
                    seen.add(n); st.append(n)
        ends = [q for q in comp if len(adj[q]) == 1]
        if len(comp) < 2 or len(ends) != 2 or any(len(adj[q]) > 2 for q in comp):
            blockers.append(comp); continue
        info = []
        for e in ends:
            nb = next(iter(adj[e]))
            d = (e[0] - nb[0], e[1] - nb[1])
            info.append((head_score(ink, W, H, px(e[0]), py(e[1]), d[0], d[1]), e, nb, d))
        s0, s1 = info[0][0], info[1][0]
        if max(s0, s1) < 8 or max(s0, s1) < 2.5 * min(s0, s1) + 6:
            blockers.append(comp); continue
        sc, e, nb, d = max(info, key=lambda t: t[0])
        arrows.append(dict(head=e, dir=d, cells=set(comp), scores=(s0, s1),
                           hx=px(e[0]), hy=py(e[1]),
                           tx=(px(e[0]) + px(nb[0])) / 2, ty=(py(e[1]) + py(nb[1])) / 2))
    return arrows, blockers, NC, NR, px, py, adj


def ray(a, NC, NR):
    c, r = a["head"]; dc, dr = a["dir"]
    out = []
    c += dc; r += dr
    while 0 <= c < NC and 0 <= r < NR:
        out.append((c, r)); c += dc; r += dr
    return out


def solve(arrows, blockers, NC, NR):
    owner = {}
    for i, a in enumerate(arrows):
        for p in a["cells"]:
            owner[p] = i
    for comp in blockers:
        for p in comp:
            owner[p] = -2
    alive = set(range(len(arrows)))
    rounds = []
    while alive:
        free = []
        for i in sorted(alive):
            ok = True
            for p in ray(arrows[i], NC, NR):
                o = owner.get(p)
                if o is not None and o != i and (o == -2 or o in alive):
                    ok = False; break
            if ok:
                free.append(i)
        if not free:
            break
        rounds.append(free); alive -= set(free)
    return rounds, alive
