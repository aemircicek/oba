#!/usr/bin/env python3
"""Compares two JMH result files (lower is better: every benchmark reports average time).

    compare.py base.json head.json [--max-slowdown 0.30]

Prints a table (Markdown, for a CI summary) and exits with 1 if a benchmark became slower than
the allowed fraction, beyond the noise both runs report.
"""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as f:
        return {r["benchmark"].split(".", 2)[-1]: r["primaryMetric"] for r in json.load(f)}


def main():
    args = sys.argv[1:]
    limit = 0.30
    if "--max-slowdown" in args:
        i = args.index("--max-slowdown")
        limit = float(args[i + 1])
        del args[i:i + 2]
    base, head = load(args[0]), load(args[1])
    worse = []
    print("| benchmark | before | after | change |")
    print("|---|---:|---:|---:|")
    for name in sorted(head):
        h = head[name]
        unit = h["scoreUnit"]
        if name not in base:
            print(f"| {name} | – | {h['score']:.3f} {unit} | new |")
            continue
        b = base[name]
        change = (h["score"] - b["score"]) / b["score"]
        noise = (abs(h.get("scoreError") or 0) + abs(b.get("scoreError") or 0)) / b["score"]
        flag = ""
        if change > limit and change - noise > limit:
            flag = " ⚠"
            worse.append(name)
        print(f"| {name} | {b['score']:.3f} {unit} | {h['score']:.3f} {unit} | {change:+.0%}{flag} |")
    if worse:
        print(f"\nSlower by more than {limit:.0%}: {', '.join(worse)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
