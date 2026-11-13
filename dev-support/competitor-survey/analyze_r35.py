#!/usr/bin/env python
# r35 marketplace survey analyzer. Reads JSON from DATA, writes delta-table.json to DATA.
# Usage: python analyze_r35.py <DATA_DIR>
import json, os, sys, glob, re
from datetime import datetime, timezone

DATA = sys.argv[1] if len(sys.argv) > 1 else r"C:/Users/USER/AppData/Local/Temp/mpsv-survey-r35"
R34_SNAP = datetime(2026, 10, 31, 1, 42, 28, tzinfo=timezone.utc)
R35_SNAP = datetime(2026, 11, 3, 0, 1, 35, tzinfo=timezone.utc)
WINDOW_DAYS = (R35_SNAP - R34_SNAP).total_seconds() / 86400.0
DOUBLE_ZERO_SINCE = datetime(2026, 8, 21, tzinfo=timezone.utc)

# r34 baseline: downloads, rating(mean), votes, last-update version/date string, extra
R34 = {
    15075: dict(name="JPA Buddy", dl=6866820, rating=4.94, votes=255, ver="263.7307.112", ud="2026-10-30"),
    10119: dict(name="MyBatisX", dl=5544769, rating=4.73, votes=124, ver="1.7.7", ud="2026-09-15", unapproved=True),
    9837:  dict(name="CHP直装版(MyBatisCodeHelperPro)", dl=1447677, rating=4.96, votes=197, ver="3.6.8+2321", ud="2026-10-27"),
    13905: dict(name="MyBatis Log(obroom)", dl=633058, rating=4.30, votes=23, ver=None, ud="2026-06-11"),
    17898: dict(name="MyBatis Log Free", dl=554527, rating=4.35, votes=17, ver=None, ud="2026-07-27"),
    12670: dict(name="MyBatisPlus", dl=472596, rating=4.57, votes=14, ver=None, ud="2022-07-23"),
    33684: dict(name="SQLite Lens", dl=4917, rating=4.50, votes=2, ver=None, ud="2026-10-17"),
    33397: dict(name="JSONL Lens", dl=1767, rating=None, votes=0, ver="2.0.1", ud="2026-10-28"),
    33633: dict(name="SQL Visualizer", dl=656, rating=None, votes=0, ver="v2026.10.30", ud="2026-10-29", top_update=1206029),
    30447: dict(name="zMyBatis", dl=131, rating=None, votes=0, ver="26.04.02.052614", ud="2026-04-01"),
    # watch list: absolute baselines only where r34 report gave them
    35174: dict(name="MybatisToolPlus", dl=93),
    35635: dict(name="Toolkit for DuckDB", dl=0),
    34107: dict(name="Guard家族A", dl=None),
    34131: dict(name="Guard家族B", dl=None),
    34456: dict(name="Guard家族C", dl=None),
    34687: dict(name="TableBridge", dl=None, unapproved=True),
    34028: dict(name="Hibernate Debugger", dl=None, ver="263.7307.101"),
    25744: dict(name="MyBatiSuite", dl=None),
    21812: dict(name="r34掉出A", dl=None),
    34166: dict(name="r34掉出B", dl=None),
    14522: dict(name="CHP MP付费版(Marketplace Edition)", dl=None),  # r34 gave only increment (+209), no absolute
}
CORE_IDS = [15075, 10119, 9837, 13905, 17898, 12670, 33684, 33397, 33633, 30447, 14522]
ALL_IDS = sorted(R34.keys())

def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

def ms_to_dt(v):
    if v is None: return None
    v = int(v)
    if v > 10**12: v = v / 1000.0
    return datetime.fromtimestamp(v, tz=timezone.utc)

def rating_from_votes(r):
    votes = r.get("votes", {})
    if not isinstance(votes, dict): return None, 0
    total = sum(votes.values()); wsum = sum(int(k) * v for k, v in votes.items())
    return (round(wsum / total, 2) if total else None), total

result = {}
for pid in ALL_IDS:
    b = load(f"{DATA}/base_{pid}.json")
    u = load(f"{DATA}/updates_{pid}.json")
    r = load(f"{DATA}/rating_{pid}.json")
    mean, votes = rating_from_votes(r)
    comments = []
    for f in sorted(glob.glob(f"{DATA}/comments_{pid}_p*.json")):
        comments.extend(load(f))
    cdates = [ms_to_dt(c.get("cdate")) for c in comments if c.get("cdate")]
    pend = [{"id": x.get("id"), "version": x.get("version"), "cdate": x.get("cdate")}
            for x in u if not x.get("approve")]
    top = u[0] if u else {}
    b34 = R34[pid]
    dl_now, dl_34 = b["downloads"], b34.get("dl")
    entry = dict(
        id=pid, name=b.get("name"),
        downloads_now=dl_now, downloads_r34=dl_34,
        delta=(dl_now - dl_34) if dl_34 is not None else None,
        daily=(round((dl_now - dl_34) / WINDOW_DAYS, 2) if dl_34 is not None else None),
        rating_now=mean, votes_now=votes, rating_r34=b34.get("rating"), votes_r34=b34.get("votes"),
        version_now=top.get("version"), version_r34=b34.get("ver"),
        top_update_id=top.get("id"),
        last_update_dt=ms_to_dt(b.get("cdate")).strftime("%Y-%m-%d") if b.get("cdate") else None,
        last_update_r34=b34.get("ud"),
        hasUnapprovedUpdate=b.get("hasUnapprovedUpdate"),
        hasUnapprovedUpdate_r34=b34.get("unapproved", False),
        pending_updates=pend,
        cdate_now=b.get("cdate"),
        comments_count=len(comments),
        comments_last=(max(cdates).strftime("%Y-%m-%d") if cdates else None),
        comments_new_since_r34=sum(1 for d in cdates if d > R34_SNAP),
        vendor=(b.get("vendor") or {}).get("name"),
        pricingModel=b.get("pricingModel"),
    )
    result[pid] = entry

new_rating_votes = {pid: e for pid, e in result.items() if e["votes_r34"] is not None and e["votes_now"] > e["votes_r34"]}
flipped_unapproved = {pid: e for pid, e in result.items() if e["hasUnapprovedUpdate"] != e["hasUnapprovedUpdate_r34"]}

# keyword rankings
KWS = dict(sqlexplain="sql explain", sqlpreview="sql preview", sqllog="sql log",
           sqlvisualizer="sql visualizer", mybatis="mybatis", mybatislog="mybatis log",
           jpasql="jpa sql", sqlformatter="sql formatter")
kw_out = {}
kw_id_names = {}  # id -> (name, downloads) seen in keyword results
for slug, label in KWS.items():
    raw = []
    total = None
    for f in sorted(glob.glob(f"{DATA}/kw_{slug}_o*.json"), key=lambda p: int(re.search(r"_o(\d+)", p).group(1))):
        d = load(f)
        total = d.get("total", total)
        raw.extend(d.get("plugins", []))
    raw_pos = [i + 1 for i, p in enumerate(raw) if p["id"] == 33633]
    seen, dedup = set(), []
    for p in raw:
        if p["id"] not in seen:
            seen.add(p["id"]); dedup.append(p)
    dedup_pos = [i + 1 for i, p in enumerate(dedup) if p["id"] == 33633]
    kw_out[label] = dict(total=total, raw_positions=raw_pos, dedup_positions=dedup_pos, raw_count=len(raw))
    for p in raw:
        if p["id"] not in kw_id_names:
            kw_id_names[p["id"]] = dict(name=p["name"], downloads=p.get("downloads", 0), keywords=[label])
        else:
            kw_id_names[p["id"]]["keywords"].append(label)

known_ids = set(ALL_IDS)
new_faces = {pid: v for pid, v in sorted(kw_id_names.items()) if pid not in known_ids}
zero_dl_faces = {pid: v for pid, v in new_faces.items() if v["downloads"] == 0}

pv = result[33633]
double_zero_days = (R35_SNAP - DOUBLE_ZERO_SINCE).days

# candidate new faces: first release (oldest update) after r34 snapshot
fresh = []
for f in sorted(glob.glob(f"{DATA}/cand_updates_*.json")):
    cid = int(re.search(r"cand_updates_(\d+)", f).group(1))
    u = load(f)
    if not u:
        continue
    first = int(u[-1]["cdate"]); first = first / 1000.0 if first > 10**12 else float(first)
    if first > R34_SNAP.timestamp():
        fresh.append(dict(id=cid, name=kw_id_names.get(cid, {}).get("name"),
                          downloads=kw_id_names.get(cid, {}).get("downloads"),
                          first_release=datetime.fromtimestamp(first, tz=timezone.utc).strftime("%Y-%m-%d")))
fresh.sort(key=lambda x: x["id"])

# request count from log
ok = fail = 0
try:
    for line in open(f"{DATA}/_reqlog.txt", encoding="utf-8"):
        if "-> 200" in line: ok += 1
        elif "-> " in line: fail += 1
except FileNotFoundError:
    pass

summary = dict(
    r34_snapshot=R34_SNAP.strftime("%Y-%m-%dT%H:%M:%SZ"),
    r35_snapshot=R35_SNAP.strftime("%Y-%m-%dT%H:%M:%SZ"),
    window_days=round(WINDOW_DAYS, 4),
    requests_ok=ok, requests_fail=fail,
    core10_sum_r34=sum(R34[p]["dl"] for p in CORE_IDS[:-1] if R34[p].get("dl")),
    core10_sum_r35=sum(result[p]["downloads_now"] for p in CORE_IDS[:-1]),
    all21_sum_r35=sum(result[p]["downloads_now"] for p in ALL_IDS),
    zero_or_neg=[dict(id=p, name=result[p]["name"], delta=result[p]["delta"]) for p in ALL_IDS if result[p]["delta"] == 0],
    negative=[dict(id=p, name=result[p]["name"], delta=result[p]["delta"]) for p in ALL_IDS if (result[p]["delta"] or 0) < 0],
    new_rating_votes=new_rating_votes,
    flipped_unapproved={p: dict(now=result[p]["hasUnapprovedUpdate"], r34=result[p]["hasUnapprovedUpdate_r34"]) for p, e in flipped_unapproved.items()},
    double_zero_days=double_zero_days,
    plugin33633=dict(downloads=pv["downloads_now"], top_update_id=pv["top_update_id"],
                     version=pv["version_now"], cdate=pv["cdate_now"],
                     cdate_iso=ms_to_dt(pv["cdate_now"]).strftime("%Y-%m-%dT%H:%M:%SZ") if pv["cdate_now"] else None,
                     votes=0, comments=0, double_zero_days=double_zero_days),
    keywords=kw_out,
    fresh_faces=fresh,
    new_faces=new_faces,
    zero_download_faces=zero_dl_faces,
    plugins={str(p): result[p] for p in ALL_IDS},
)
with open(f"{DATA}/delta-table.json", "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=1, default=str)

# console digest
print(f"window_days={WINDOW_DAYS:.4f}  double_zero_days={double_zero_days}")
print("pid  name  dl(r34->now)  delta  daily  rating(r34->now)  votes(r34->now)  ver(r34->now)  topid  unapp  cmts(new)")
for p in ALL_IDS:
    e = result[p]
    print(f"{p} {e['name']} {e['downloads_r34']}->{e['downloads_now']} d={e['delta']} daily={e['daily']} "
          f"r={e['rating_r34']}->{e['rating_now']} v={e['votes_r34']}->{e['votes_now']} "
          f"ver={e['version_r34']}->{e['version_now']} top={e['top_update_id']} unapp={e['hasUnapprovedUpdate']} "
          f"cmts={e['comments_count']}(+{e['comments_new_since_r34']},last={e['comments_last']}) pend={e['pending_updates']}")
print("--- keywords ---")
for k, v in kw_out.items():
    print(f"{k}: total={v['total']} raw={v['raw_positions']} dedup={v['dedup_positions']}")
print(f"new_faces={len(new_faces)} zero_dl={len(zero_dl_faces)}")
