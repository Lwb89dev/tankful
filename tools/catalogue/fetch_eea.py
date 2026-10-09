#!/usr/bin/env python3
"""Download grouped EEA CO2-car rows that already publish one fuel consumption.

The public table is one row per registration. This script asks the EEA SQL
service for each make, grouped so identical type-approval figures collapse.
It does not download the registration microdata.
"""

import json
import os
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "catalog", "raw", "eea")
UA = "Tankful catalogue builder (local research)"
HITS = 50000

TABLES = {
    2021: "co2cars_2021Fv24",
    2022: "co2cars_2022Fv26",
    2023: "co2cars_2023Fv28",
    2024: "co2cars_2024Pv29",
    2025: "co2cars_2025Pv31",
}


def sql_get(sql):
    url = "https://discodata.eea.europa.eu/sql?query=" + urllib.parse.quote(sql)
    url += "&p=1&nrOfHits=" + str(HITS)
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    last = None
    for _ in range(4):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                body = json.loads(response.read().decode("utf-8"))
            if body.get("errors"):
                raise RuntimeError(body["errors"])
            return body.get("results") or []
        except Exception as error:
            last = error
            time.sleep(2)
    raise RuntimeError(last)


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def makes(table):
    rows = sql_get(
        "SELECT Mk AS mk FROM [CO2Emission].[latest].[" + table + "] "
        "WHERE Fc IS NOT NULL AND Mk IS NOT NULL GROUP BY Mk"
    )
    found = []
    for row in rows:
        name = (row.get("mk") or "").strip()
        if any(character.isalpha() for character in name):
            found.append(name)
    return found


def groups(table, make):
    sql = (
        "SELECT Mk, Cn, Ft, Fm, [Ec (cm3)] AS ec, [Ep (KW)] AS ep, "
        "[Ewltp (g/km)] AS co2, Fc FROM [CO2Emission].[latest].[" + table + "] "
        "WHERE Fc IS NOT NULL AND Mk = " + quote(make) + " AND Cn IS NOT NULL "
        "GROUP BY Mk, Cn, Ft, Fm, [Ec (cm3)], [Ep (KW)], [Ewltp (g/km)], Fc"
    )
    return sql_get(sql)


def pull(table, make):
    try:
        return make, groups(table, make), None
    except Exception as error:
        return make, [], str(error)


def fetch_all(table, names):
    found = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        jobs = [pool.submit(pull, table, make) for make in names]
        for job in as_completed(jobs):
            found.append(job.result())
            if len(found) % 25 == 0:
                print(table, len(found), "/", len(names), flush=True)
    return found


def write_outcomes(handle, year, outcomes):
    failures = []
    for make, rows, error in outcomes:
        if error:
            failures.append(make + " " + error)
            continue
        for row in rows:
            row["year"] = year
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    return failures


def write_year(year, table):
    destination = os.path.join(OUT_DIR, str(year) + ".jsonl")
    done = destination + ".done"
    if os.path.exists(done):
        print("skip", year, flush=True)
        return
    names = makes(table)
    print(year, "makes", len(names), flush=True)
    outcomes = fetch_all(table, names)
    partial = destination + ".part"
    with open(partial, "w", encoding="utf-8") as handle:
        failures = write_outcomes(handle, year, outcomes)
    os.replace(partial, destination)
    with open(done, "w", encoding="utf-8") as handle:
        handle.write("failures " + str(len(failures)) + "\n")
        for line in failures:
            handle.write(line + "\n")
    print("wrote", year, "failures", len(failures), flush=True)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for year, table in TABLES.items():
        write_year(year, table)
    print("eea fetch done")


if __name__ == "__main__":
    main()
