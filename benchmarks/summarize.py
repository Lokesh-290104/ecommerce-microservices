"""Turns benchmarks/results/<variant>/ into the README table (step 8).

For each variant and endpoint: median p50 / p95 latency across the k6 runs, throughput, and the
average SQL statements per request (from X-SQL-Count). Usage: python benchmarks/summarize.py
"""
import json
import statistics
import sys
from pathlib import Path

RESULTS = Path(__file__).parent / "results"
ORDER = ["baseline", "batchsize", "twostep"]


def sql_counts(variant_dir: Path) -> dict[str, float]:
    per_endpoint: dict[str, list[int]] = {}
    for line in (variant_dir / "sql-counts.txt").read_text().splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            per_endpoint.setdefault(parts[0], []).append(int(parts[1]))
    return {e: sum(v) / len(v) for e, v in per_endpoint.items()}


def main() -> int:
    variants = sorted((d for d in RESULTS.iterdir() if d.is_dir()),
                      key=lambda d: ORDER.index(d.name) if d.name in ORDER else len(ORDER))
    print("| Variant | Endpoint | SQL / request | p50 (ms) | p95 (ms) | Requests/s | Runs |")
    print("|---|---|---|---|---|---|---|")
    for d in variants:
        sql = sql_counts(d)
        for endpoint in ("products", "orders"):
            runs = sorted(d.glob(f"{endpoint}-run*.json"))
            if not runs:
                continue
            metrics = [json.loads(r.read_text())["metrics"] for r in runs]
            p50 = statistics.median(m["http_req_duration"]["p(50)"] for m in metrics)
            p95 = statistics.median(m["http_req_duration"]["p(95)"] for m in metrics)
            rps = statistics.median(m["http_reqs"]["rate"] for m in metrics)
            print(f"| {d.name} | {endpoint} | {sql.get(endpoint, float('nan')):.1f} | {p50:.1f} | {p95:.1f} "
                  f"| {rps:.0f} | {len(runs)} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
