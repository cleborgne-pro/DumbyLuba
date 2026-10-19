#!/usr/bin/env python3
"""Report derived from the exported measurements; no synthesized performance figure."""
import collections
import json
import pathlib
import statistics
import sys

root = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in (root / 'results.jsonl').read_text().splitlines()]
groups = collections.defaultdict(list)
for row in rows:
    groups[row['case'], row['strategy']].append(row)

def value(row, key, digits=2):
    v = row.get(key)
    return '—' if v is None else f'{v:.{digits}f}'

text = ['# Campaign results', '',
        'Source: [JSONL](results.jsonl), [CSV](results.csv), [manifest](manifest.json).', '',
        'Distances in meters, estimated duration without recharging in seconds, areas in m², CPU in ms. '
        'Metrics missing on a failure are undefined, never zero. '
        'Each row summarizes the repetitions of one strategy; CPU is their median.', '',
        '| Map | Strategy | Status | Selected candidate | Distance | Transit | U-turns | Duration | Median CPU |',
        '|---|---|---|---|---:|---:|---:|---:|---:|']
for (name, strategy), measured in groups.items():
    r = measured[0]
    assert len({item['planHash'] for item in measured}) == 1
    text.append(f"| {name.strip()} | {strategy} | {r['status']} | {r['winner']} | "
                f"{value(r,'distanceM')} | {value(r,'transitM')} | {value(r,'uTurns',0)} | "
                f"{value(r,'durationSeconds')} | {statistics.median(item['cpuMs'] for item in measured):.2f} |")
text += ['', '| Map | Strategy | Target | Covered | Uncovered | Revisited | Extra visits | Violations |',
         '|---|---|---:|---:|---:|---:|---:|---:|']
for (name, strategy), measured in groups.items():
    r = measured[0]
    text.append(f'| {name.strip()} | {strategy} | '+ ' | '.join(value(r,key,0 if key=='geometricViolations' else 4) for key in
                ('targetAreaM2','coveredAreaM2','uncoveredAreaM2','repeatedAreaM2','extraVisitAreaM2','geometricViolations'))+' |')
text += ['', 'An extra visit is not necessarily avoidable. The score certifies neither the real mechanics '
         'nor the duration with recharging. The reports keep the candidates\' rejections and diagnostics.', '']
(root / 'SUMMARY.md').write_text('\n'.join(text))
