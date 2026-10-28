"""Cell-step simulation of the game: does tapping a batch of 'free' arrows together cause collisions?"""
import sys
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref3

S = os.environ.get('SHOTS', 'shots/')


def ordered_path(a, adj):
    """cells from tail to head"""
    head = a['head']
    path = [head]
    prev = None
    cur = head
    while True:
        nxt = [n for n in adj[cur] if n in a['cells'] and n != prev]
        if not nxt:
            break
        prev, cur = cur, nxt[0]
        path.append(cur)
    return path[::-1]  # tail ... head


def simulate(arrows, adj, NC, NR, mode, stagger, blockers=()):
    """mode 'naive': tap every free arrow of the round together; 'safe': only ray-disjoint ones.
    stagger: steps between consecutive taps in a batch. returns (collisions, rounds)"""
    paths = [ordered_path(a, adj) for a in arrows]
    alive = set(range(len(arrows)))
    collisions = 0
    rounds = 0
    blocked_cells = set(p for comp in blockers for p in comp)
    while alive:
        owner = {}
        for i in alive:
            for p in paths[i]:
                owner[p] = i
        free = []
        for i in sorted(alive):
            ok = True
            for p in ref3.ray(arrows[i], NC, NR):
                if p in blocked_cells or (p in owner and owner[p] != i):
                    ok = False; break
            if ok:
                free.append(i)
        if not free:
            break
        if mode == 'safe':
            used = set(); chosen = []
            for i in sorted(free, key=lambda i: len(ref3.ray(arrows[i], NC, NR))):
                r = set(ref3.ray(arrows[i], NC, NR))
                if r & used:
                    continue
                used |= r | set(paths[i]); chosen.append(i)
            free = chosen
        # simulate batch: each snake is a deque of cells; extended path = body cells + ray + beyond
        snakes = {}
        for k, i in enumerate(free):
            body = list(paths[i])
            a = arrows[i]
            dc, dr = a['dir']
            hc, hr = a['head']
            ext = []
            c, r = hc + dc, hr + dr
            for _ in range(len(body) + max(NC, NR) + 4):
                ext.append((c, r)); c += dc; r += dr
            snakes[i] = dict(cells=body, ext=ext, step=0, start=k * stagger, dead=False)
        still = set(alive) - set(free)
        static = {p: i for i in still for p in paths[i]}
        t = 0
        active = set(free)
        hit = set()
        while active and t < 4000:
            for i in list(active):
                s = snakes[i]
                if t < s['start']:
                    continue
                # advance one cell
                if s['step'] < len(s['ext']):
                    s['cells'] = s['cells'][1:] + [s['ext'][s['step']]]
                    s['step'] += 1
                if all(not (0 <= c < NC and 0 <= r < NR) for c, r in s['cells']):
                    active.discard(i)
            # collision check among moving snakes' heads vs everything else
            occ = {}
            for i in free:
                s = snakes[i]
                if i in hit or t < s['start']:
                    continue
                for p in s['cells']:
                    occ.setdefault(p, set()).add(i)
            for i in free:
                s = snakes[i]
                if i in hit or t < s['start'] or s['step'] == 0:
                    continue
                h = s['cells'][-1]
                if not (0 <= h[0] < NC and 0 <= h[1] < NR):
                    continue
                if (h in static) or any(j != i for j in occ.get(h, ())):
                    hit.add(i)
            t += 1
        collisions += len(hit)
        alive -= set(free)
        rounds += 1
    return collisions, rounds, len(alive)


if __name__ == '__main__':
    for nm in ['L01', 'L33', 'L34', 'L35', 'L39']:
        im, ink, struct, W, H = ref3.load(S + f'shots/{nm}.jpg')
        cal = ref3.calibrate(struct, W, H)
        arrows, blockers, NC, NR, px, py, adj = ref3.parse(ink, W, H, cal)
        if nm == 'L39':
            blockers = []
        res = []
        for mode in ('naive', 'safe'):
            for stagger in (0, 3):
                c, r, left = simulate(arrows, adj, NC, NR, mode, stagger, blockers)
                res.append(f"{mode}/stagger{stagger}: collisions {c} rounds {r}")
        print(nm, f"{len(arrows)} arrows |", ' | '.join(res))
