"""The Xens that did best in a test, for the next generation to learn from: python -m xen.ex1.elite track.tsv server.log

Each Xen gets a score from how it got on (iron before the first night, iron at all, a diamond, an iron pickaxe) less its
deaths; the top third (and anyone dead-free with a diamond) are named, one a line, for xen.ex1.train --elite.
"""
import argparse
import re
import sys


def scores(track_path, log_path=None):
    """{name: score} from prog5's track (gametime, name, pickaxe tier, iron, diamonds) and the server log's deaths."""
    first_iron, first_dia, best_tier = {}, {}, {}
    with open(track_path) as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            t, name, tier, iron, dia = int(parts[0]), parts[1], int(parts[2] or 0), int(parts[3] or 0), int(parts[4] or 0)
            best_tier[name] = max(best_tier.get(name, 0), tier)
            if (iron > 0 or tier >= 3) and name not in first_iron:
                first_iron[name] = t
            if (dia > 0 or tier >= 4) and name not in first_dia:
                first_dia[name] = t
    deaths = {n: 0 for n in best_tier}
    if log_path:
        dead = re.compile(r"INFO\]: (\S+) (was slain|was shot|blew up|fell |drowned|burned|tried to swim|starved|suffocated|was blown|hit the ground|went up in flames|withered|was killed)")
        with open(log_path, errors="replace") as fh:
            for line in fh:
                if "<" in line or "local chat" in line and "death" not in line:
                    continue
                m = dead.search(line)
                if m and m.group(1) in deaths:
                    deaths[m.group(1)] += 1
    out = {}
    for n in best_tier:
        s = 0.0
        if n in first_iron:
            s += 2 + (2 if first_iron[n] < 12500 else 0)
        if n in first_dia:
            s += 4
        s += best_tier[n] - deaths[n] * 1.5
        out[n] = s
    return out, deaths, first_dia


def elite(track_path, log_path=None, share=1 / 3):
    sc, deaths, dia = scores(track_path, log_path)
    ranked = sorted(sc, key=lambda n: -sc[n])
    keep = set(ranked[:max(1, round(len(ranked) * share))])
    keep |= {n for n in dia if deaths.get(n, 0) == 0}
    return [n for n in ranked if n in keep], sc


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("track")
    ap.add_argument("log", nargs="?")
    a = ap.parse_args(argv)
    names, sc = elite(a.track, a.log)
    for n in sorted(sc, key=lambda n: -sc[n]):
        print(f"{'*' if n in names else ' '} {n:20s} {sc[n]:5.1f}", file=sys.stderr)
    print("\n".join(names))


if __name__ == "__main__":
    main()
