import sys
import numpy as np
from PIL import Image, ImageDraw

path = sys.argv[1]
out = sys.argv[2]
im = Image.open(path).convert("RGB")
a = np.asarray(im).astype(int)
H, W, _ = a.shape

# --- 1. ink mask (dark brown lines) ---
ref = np.array([110, 75, 45])
d = np.abs(a - ref).sum(axis=2)
mask = d < 90
y0, y1 = int(H * 0.18), int(H * 0.86)
region = np.zeros_like(mask)
region[y0:y1] = mask[y0:y1]

# --- 2. calibrate lattice from projections ---
col = region.sum(axis=0).astype(float)
row = region.sum(axis=1).astype(float)

def best_fit(proj, lo, hi):
    """return list of (score, p, x0) over candidate pitches"""
    n = len(proj)
    res = []
    for p in np.arange(lo, hi, 0.1):
        best = (-1, 0)
        for x0 in np.arange(0, p, 0.5):
            xs = np.arange(x0, n - 1, p).round().astype(int)
            s = 0.0
            for off in (-1, 0, 1):
                pass
            idx = np.clip(xs, 1, n - 2)
            s = np.maximum(np.maximum(proj[idx - 1], proj[idx]), proj[idx + 1]).sum()
            if s > best[0]:
                best = (s, x0)
        res.append((best[0], p, best[1]))
    return res

resx = best_fit(col, 18, 60)
smax = max(r[0] for r in resx)
cands = [r for r in resx if r[0] >= 0.93 * smax]
sc, pitch, x0 = max(cands, key=lambda r: r[1])
print("x fit: pitch", pitch, "x0", x0, "score", sc / smax)

# fine refinement of pitch + x0 + y0 jointly
def score_axis(proj, p, o):
    n = len(proj)
    idx = np.clip(np.arange(o, n - 1, p).round().astype(int), 1, n - 2)
    return np.maximum(np.maximum(proj[idx - 1], proj[idx]), proj[idx + 1]).sum()
bestfine = (-1, 0, 0, 0)
for p in np.arange(pitch - 1.0, pitch + 1.0, 0.02):
    sx = max((score_axis(col, p, o), o) for o in np.arange(0, p, 0.25))
    sy = max((score_axis(row, p, o), o) for o in np.arange(0, p, 0.25))
    tot = sx[0] + sy[0]
    if tot > bestfine[0]:
        bestfine = (tot, p, sx[1], sy[1])
_, pitch, x0, yy0 = bestfine
print("refined pitch", pitch, "x0", x0, "y0", yy0)
# refine on the y axis with the same pitch
bestY = (-1, 0)
for yy in np.arange(0, pitch, 0.5):
    ys = np.arange(yy, H - 1, pitch).round().astype(int)
    idx = np.clip(ys, 1, H - 2)
    s = np.maximum(np.maximum(row[idx - 1], row[idx]), row[idx + 1]).sum()
    if s > bestY[0]:
        bestY = (s, yy)
print("y0", yy0)

# local snapping to find exact line centres: use the actual peaks of projections
# bounding lattice indices: lines which have ink
xs_all = [x0 + k * pitch for k in range(int((W - x0) / pitch) + 1)]
ys_all = [yy0 + k * pitch for k in range(int((H - yy0) / pitch) + 1)]

def line_strength_x(x):
    xi = int(round(x))
    return col[max(xi - 1, 0):xi + 2].max()

def line_strength_y(y):
    yi = int(round(y))
    return row[max(yi - 1, 0):yi + 2].max()

kx = [k for k, x in enumerate(xs_all) if line_strength_x(x) > 40]
ky = [k for k, y in enumerate(ys_all) if line_strength_y(y) > 40]
kx0, kx1 = min(kx), max(kx)
ky0, ky1 = min(ky), max(ky)
print("lattice cols", kx0, kx1, "rows", ky0, ky1)
NC = kx1 - kx0 + 1
NR = ky1 - ky0 + 1
print("lattice points", NC, "x", NR)

def px(c):
    return x0 + (kx0 + c) * pitch

def py(r):
    return yy0 + (ky0 + r) * pitch

# --- 3. edges ---
def cover(xa, ya, xb, yb):
    n = 9
    hit = 0
    for t in np.linspace(0.25, 0.75, n):
        x = int(round(xa + (xb - xa) * t))
        y = int(round(ya + (yb - ya) * t))
        if mask[y - 1:y + 2, x - 1:x + 2].any():
            hit += 1
    return hit / n

edges = {}
adj = {}
for r in range(NR):
    for c in range(NC):
        for dc, dr in ((1, 0), (0, 1)):
            c2, r2 = c + dc, r + dr
            if c2 >= NC or r2 >= NR:
                continue
            if cover(px(c), py(r), px(c2), py(r2)) > 0.85:
                adj.setdefault((c, r), set()).add((c2, r2))
                adj.setdefault((c2, r2), set()).add((c, r))
print("points with ink:", len(adj))

# --- 4. components ---
seen = set()
comps = []
for p in adj:
    if p in seen:
        continue
    stack = [p]
    seen.add(p)
    comp = []
    while stack:
        q = stack.pop()
        comp.append(q)
        for n in adj[q]:
            if n not in seen:
                seen.add(n)
                stack.append(n)
    comps.append(comp)
print("components:", len(comps))

# head detection: arrowhead wings add ink perpendicular to the tip segment
def head_score(pt, direction):
    """direction = unit vector pointing outward from the body at endpoint pt"""
    c, r = pt
    x, y = px(c), py(r)
    dx, dy = direction
    px_, py_ = -dy, dx
    cnt = 0
    for back in (4, 8, 12):
        for side in (-9, -7, 7, 9):
            sx = int(round(x - dx * back + px_ * side))
            sy = int(round(y - dy * back + py_ * side))
            if 0 <= sx < W and 0 <= sy < H and mask[sy, sx]:
                cnt += 1
    return cnt

arrows = []
bad = 0
for comp in comps:
    ends = [p for p in comp if len(adj[p]) == 1]
    if len(ends) != 2 or len(comp) < 2:
        bad += 1
        continue
    scores = []
    for e in ends:
        nb = next(iter(adj[e]))
        dirv = (e[0] - nb[0], e[1] - nb[1])
        scores.append(head_score(e, dirv))
    if scores[0] == scores[1]:
        bad += 1
        continue
    hi = 0 if scores[0] > scores[1] else 1
    head = ends[hi]
    nb = next(iter(adj[head]))
    dirv = (head[0] - nb[0], head[1] - nb[1])
    arrows.append(dict(head=head, dir=dirv, cells=set(comp), scores=scores))
print("arrows:", len(arrows), "unparsed comps:", bad)

# --- 5. solve ---
owner = {}
for i, ar in enumerate(arrows):
    for p in ar["cells"]:
        owner[p] = i
alive = set(range(len(arrows)))
order = []
progress = True
while progress and alive:
    progress = False
    for i in sorted(alive):
        ar = arrows[i]
        c, r = ar["head"]
        dc, dr = ar["dir"]
        c += dc
        r += dr
        free = True
        while 0 <= c < NC and 0 <= r < NR:
            o = owner.get((c, r))
            if o is not None and o in alive and o != i:
                free = False
                break
            c += dc
            r += dr
        if free:
            order.append(i)
            alive.discard(i)
            progress = True
print("solved:", len(order), "of", len(arrows), "stuck:", len(alive))

# --- 6. render ---
dr_ = ImageDraw.Draw(im)
for n, i in enumerate(order):
    c, r = arrows[i]["head"]
    x, y = px(c), py(r)
    dr_.ellipse([x - 9, y - 9, x + 9, y + 9], fill=(220, 40, 40))
    dr_.text((x - 6, y - 6), str(n + 1), fill=(255, 255, 255))
for i in alive:
    c, r = arrows[i]["head"]
    x, y = px(c), py(r)
    dr_.ellipse([x - 9, y - 9, x + 9, y + 9], outline=(0, 0, 255), width=3)
im.save(out)
