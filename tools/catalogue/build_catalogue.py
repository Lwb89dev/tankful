#!/usr/bin/env python3
"""Build the read-only official catalogue shipped in the app.

Sources, each copied as published, with no invented consumption or CO2 figure:

- US EPA fueleconomy.gov vehicles.csv, public domain, model years from 2003.
  Combined miles per gallon and tailpipe grams per mile. Natural gas stays out:
  the file reports it in miles per gasoline gallon equivalent, which this
  logbook cannot compare with kilograms. Plug-in hybrids keep comb08 as the
  gasoline charge-sustaining figure, not as MPGe.
- ADEME car labelling, Licence Ouverte, annual files 2003-2015. Combined,
  urban, and extra-urban columns are litres per 100 km on the NEDC labelling
  cycle (the dictionary names those columns that way). The row year is the
  labelling edition. Natural gas, electricity, and E85 rows are skipped
  because their official unit is not a supported fuel figure.
- EEA CO2 monitoring, reuse with acknowledgement, registration years that
  publish Fc. The statistical metadata calls that field fuel consumption in
  l/100 km next to the WLTP CO2 figure. The row year is the registration year.
  Plug-in rows are stored as weighted, so the app does not treat them as a
  plain tank-to-tank figure.

The current ADEME car-labelling extract is not imported. Every consumption
there is a min/max band and the file has no model year.
"""

import csv
import gzip
import hashlib
import io
import os
import re
import sqlite3
import time
import unicodedata
import zipfile
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RAW = os.path.join(ROOT, "catalog", "raw")
DB_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "official_catalogue.db")
GZ_PATH = DB_PATH + ".gz"

ADEME_URL = "https://www.data.gouv.fr/datasets/emissions-de-co2-et-de-polluants-des-vehicules-commercialises-en-france"
ADEME_PROTOCOL = "data.gouv.fr/emissions-de-co2-vehicules-commercialises-en-france"
EPA_URL = "https://www.fueleconomy.gov/feg/download.shtml"
EPA_PROTOCOL = "fueleconomy.gov/feg/epadata/vehicles.csv"
EEA_URL = "https://www.eea.europa.eu/en/about/contact-us/faqs/where-can-i-find-the-co2-emission-levels-of-new-passenger-cars-and-vans-in-europe"
EEA_PROTOCOL = "discodata.eea.europa.eu/CO2Emission"

# First token is the fuel this ADEME row's consumption describes.
ADEME_FUELS = {
    "ES": ("PETROL", "ICE", "LIQUID"),
    "GO": ("DIESEL", "ICE", "LIQUID"),
    "GP": ("LPG", "ICE", "LIQUID"),
    "EH": ("PETROL", "HYBRID", "LIQUID"),
    "GH": ("DIESEL", "HYBRID", "LIQUID"),
    "EE": ("PETROL", "PHEV", "WEIGHTED"),
    "GL": ("DIESEL", "PHEV", "WEIGHTED"),
}

HEADER_MARKS = ("mixte", "conso mixte", "consommation mixte", "conso urb", "urb", "co2")
MAKE_KEYS = ("lib mrq", "lib mrq doss", "mrq utac", "marque", "marques")
MODEL_KEYS = (
    "lib mod", "lib mod doss", "mod utac", "modele", "modele version", "modeles versions",
    "modele dossier", "modele utac",
)
VARIANT_KEYS = ("dscom", "description commerciale", "designation commerciale")
FUEL_KEYS = ("energ", "cod cbr", "typ cbr", "energie", "carburant")
HYBRID_KEYS = ("hybride",)
MIXTE_KEYS = ("conso mixte", "mixte", "consommation mixte")
URBAN_KEYS = ("conso urb", "conso urb 93", "urb", "consommation urbaine")
EXTRA_KEYS = ("conso exurb", "ex urb", "exurb", "conso ex urb", "consommation extra urbaine")
CO2_KEYS = ("co2", "co2 mixte")
POWER_KEYS = ("puiss max", "puissance reelle", "puissance max", "puissance maximale")
CNIT_KEYS = ("cnit",)

POSITIONAL = (
    "marque", "modele version", "cnit", "mine", "energie",
    "puissance fiscale", "puissance reelle", "bv", "urb", "ex urb", "mixte", "co2",
)


def fold(text):
    normalized = unicodedata.normalize("NFKD", text)
    chars = [ch for ch in normalized if not unicodedata.combining(ch)]
    return "".join(chars).lower()


def norm_header(text):
    folded = fold(text).replace("_", " ").replace("-", " ").replace(",", " ")
    folded = re.sub(r"\([^)]*\)", " ", folded)
    return " ".join(folded.split())


def parse_number(raw):
    if raw is None:
        return None
    text = str(raw).strip().replace(" ", "").replace(",", ".")
    if text.lower() in ("", "-", "nd", "n/a", "none"):
        return None
    try:
        return Decimal(text)
    except InvalidOperation:
        return None


def unscaled(value, scale):
    quantum = Decimal(1).scaleb(-scale)
    rounded = value.quantize(quantum, rounding=ROUND_HALF_UP)
    return int(rounded.scaleb(scale))


def pick(row, keys):
    for key in keys:
        value = row.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def consumption(raw):
    number = parse_number(raw)
    if number is None or number <= 0 or number >= 100:
        return None
    return unscaled(number, 1)


def grams(raw):
    number = parse_number(raw)
    if number is None or number <= 0 or number > 1000:
        return None
    return unscaled(number, 0)


def kilowatts(raw):
    number = parse_number(raw)
    if number is None or number <= 0 or number > 2000:
        return None
    return unscaled(number, 0)


def ademe_identity(code, hybrid_text):
    token = code.split("/")[0].strip().upper()
    mapped = ADEME_FUELS.get(token)
    if mapped is None:
        return None
    fuel, train, measure = mapped
    flag = hybrid_text.strip().lower()
    if flag in ("oui", "o", "yes", "1") and train == "ICE":
        train = "HYBRID"
    return fuel, train, measure


def map_ademe(row, year):
    identity = ademe_identity(pick(row, FUEL_KEYS), pick(row, HYBRID_KEYS))
    if identity is None:
        return None
    combined = consumption(pick(row, MIXTE_KEYS))
    if combined is None:
        return None
    make = pick(row, MAKE_KEYS)
    model = pick(row, MODEL_KEYS)
    if not make or not model:
        return None
    fuel, train, measure = identity
    variant = pick(row, VARIANT_KEYS)
    if variant == model:
        variant = ""
    return record(
        "ADEME", make, model, variant, year, fuel, train, "NEDC", measure,
        "L_PER_100KM", 1, combined,
        optional_same(pick(row, URBAN_KEYS)),
        optional_same(pick(row, EXTRA_KEYS)),
        None, None, grams(pick(row, CO2_KEYS)), 0, kilowatts(pick(row, POWER_KEYS)),
        pick(row, CNIT_KEYS),
    )


def optional_same(raw):
    return consumption(raw)


def map_epa(row):
    year = parse_number(row.get("year"))
    if year is None:
        return None
    year = int(year)
    if year < 2003:
        return None
    identity = epa_identity(row.get("fuelType1") or "", row.get("atvType") or "")
    if identity is None:
        return None
    combined = parse_number(row.get("comb08"))
    if combined is None or combined <= 0 or combined >= 200:
        return None
    make = (row.get("make") or "").strip()
    model = (row.get("model") or "").strip()
    if not make or not model:
        return None
    fuel, train, measure = identity
    return record(
        "EPA", make, model, epa_variant(row), year, fuel, train, "EPA", measure,
        "US_MPG", 0, unscaled(combined, 0),
        None, None, mpg(row.get("city08")), mpg(row.get("highway08")),
        grams(row.get("co2TailpipeGpm")), 1, None, str(row.get("id") or ""),
    )


def epa_identity(fuel_type, atv):
    text = fuel_type.lower()
    if "electric" in text or "hydrogen" in text or "natural gas" in text:
        return None
    if text == "cng" or atv in ("CNG", "FCV", "EV", "eFCV"):
        return None
    fuel = None
    if "diesel" in text:
        fuel = "DIESEL"
    elif "gasoline" in text:
        fuel = "PETROL"
    elif "propane" in text or "lpg" in text:
        fuel = "LPG"
    if fuel is None:
        return None
    if atv == "Plug-in Hybrid":
        return fuel, "PHEV", "CHARGE_SUSTAINING"
    if atv == "Hybrid":
        return fuel, "HYBRID", "LIQUID"
    return fuel, "ICE", "LIQUID"


def epa_variant(row):
    parts = []
    displ = (row.get("displ") or "").strip()
    trany = (row.get("trany") or "").strip()
    if displ and displ != "0":
        parts.append(displ + " L")
    if trany:
        parts.append(trany)
    return " · ".join(parts)


def mpg(raw):
    number = parse_number(raw)
    if number is None or number <= 0 or number >= 200:
        return None
    return unscaled(number, 0)


def map_eea(row):
    identity = eea_identity(row.get("Ft") or "", row.get("Fm") or "")
    if identity is None:
        return None
    combined = consumption(row.get("Fc"))
    if combined is None:
        return None
    make = str(row.get("Mk") or "").strip()
    model = str(row.get("Cn") or "").strip()
    year = row.get("year")
    if not make or not model or not year:
        return None
    fuel, train, measure = identity
    return record(
        "EEA", make, model, eea_variant(row), int(year), fuel, train, "WLTP", measure,
        "L_PER_100KM", 1, combined,
        None, None, None, None, grams(row.get("co2")), 0, kilowatts(row.get("ep")), "",
    )


def eea_identity(fuel_type, mode):
    text = fuel_type.strip().lower()
    kind = mode.strip().upper()
    if text in ("electric", "hydrogen", "ng", "e85"):
        return None
    fuel = None
    if text.startswith("petrol"):
        fuel = "PETROL"
    elif text.startswith("diesel"):
        fuel = "DIESEL"
    elif text.startswith("lpg"):
        fuel = "LPG"
    if fuel is None:
        return None
    if kind == "P":
        return fuel, "PHEV", "WEIGHTED"
    if kind == "H":
        return fuel, "HYBRID", "LIQUID"
    if kind in ("M", "B", "F"):
        return fuel, "ICE", "LIQUID"
    return None


def cubic_centimetres(raw):
    number = parse_number(raw)
    if number is None or number <= 0 or number > 10000:
        return None
    return unscaled(number, 0)


def eea_variant(row):
    parts = []
    capacity = cubic_centimetres(row.get("ec"))
    power = kilowatts(row.get("ep"))
    if capacity:
        parts.append(str(capacity) + " cm3")
    if power:
        parts.append(str(power) + " kW")
    return " · ".join(parts)


def record(source, make, model, variant, year, fuel, train, cycle, measure, unit, scale, combined, urban, extra, city, highway, co2, per_mile, power, source_id):
    label = fold(" ".join([make, model, variant, str(year), fuel]))
    identity = "|".join([source, str(year), source_id, make, model, variant, fuel, train, str(combined), str(co2)])
    digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16]
    row_id = source.lower() + ":" + digest
    return {
        "id": row_id,
        "source": source,
        "make": make,
        "model": model,
        "variant": variant,
        "year": year,
        "fuel": fuel,
        "powertrain": train,
        "test_cycle": cycle,
        "measure_kind": measure,
        "combined_unit": unit,
        "combined_scale": scale,
        "combined_unscaled": combined,
        "urban_unscaled": urban,
        "extra_unscaled": extra,
        "city_unscaled": city,
        "highway_unscaled": highway,
        "co2_unscaled": co2,
        "co2_grams_per_mile": per_mile,
        "power_kw": power,
        "label": label,
    }


def find_header(rows):
    limit = min(8, len(rows))
    index = 0
    while index < limit:
        cells = [norm_header(cell) for cell in rows[index]]
        joined = " ".join(cells)
        if any(mark in joined for mark in HEADER_MARKS) and ("marque" in joined or "lib mrq" in joined or "cnit" in joined or "modele" in joined):
            return index
        index += 1
    return None


def positional(raw):
    if len(raw) < 12:
        return None
    fuel = raw[4].strip().upper()
    if fuel.split("/")[0] not in ADEME_FUELS and fuel not in ("EL", "GN", "FE", "EN"):
        return None
    paired = list(zip(POSITIONAL, raw))
    return {key: value for key, value in paired}


def iter_table(rows):
    header_at = find_header(rows)
    if header_at is None:
        for raw in rows:
            mapped = positional(raw)
            if mapped is not None:
                yield mapped
        return
    header = [norm_header(cell) for cell in rows[header_at]]
    for raw in rows[header_at + 1:]:
        values = list(raw) + [""] * max(0, len(header) - len(raw))
        yield {key: values[pos] for pos, key in enumerate(header) if key}


def cell_text(cell):
    import xlrd
    if cell.ctype == xlrd.XL_CELL_NUMBER:
        if cell.value == int(cell.value):
            return str(int(cell.value))
        return format(cell.value, "f")
    if cell.ctype == xlrd.XL_CELL_EMPTY:
        return ""
    return str(cell.value).strip()


def sheet_matrix(sheet):
    matrix = []
    for row_index in range(sheet.nrows):
        matrix.append([cell_text(sheet.cell(row_index, col)) for col in range(sheet.ncols)])
    return matrix


def chosen_sheets(book):
    complete = [sheet for sheet in book.sheets() if "complet" in sheet.name.lower()]
    if complete:
        return complete
    return [sheet for sheet in book.sheets() if "palmares" not in sheet.name.lower()]


def read_xls_year(path, year):
    import xlrd
    book = xlrd.open_workbook(path)
    found = []
    for sheet in chosen_sheets(book):
        for row in iter_table(sheet_matrix(sheet)):
            mapped = map_ademe(row, year)
            if mapped is not None:
                found.append(mapped)
    return found


def read_text(data):
    for encoding in ("utf-8-sig", "cp1252"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("latin-1")


def read_delimited(text):
    first = text.splitlines()[0] if text else ""
    delimiter = ";" if first.count(";") >= first.count(",") else ","
    return csv.reader(io.StringIO(text), delimiter=delimiter)


def read_csv_bytes(data, year):
    matrix = [list(row) for row in read_delimited(read_text(data))]
    found = []
    for row in iter_table(matrix):
        mapped = map_ademe(row, year)
        if mapped is not None:
            found.append(mapped)
    return found


def load_ademe():
    rows = []
    for year in range(2003, 2012):
        path = os.path.join(RAW, "xls", str(year) + ".xls")
        loaded = read_xls_year(path, year)
        print("ademe", year, len(loaded), flush=True)
        rows.extend(loaded)
    for year in range(2012, 2016):
        archive = zipfile.ZipFile(os.path.join(RAW, "ademe-" + str(year) + ".zip"))
        loaded = read_csv_bytes(archive.read(archive.namelist()[0]), year)
        print("ademe", year, len(loaded), flush=True)
        rows.extend(loaded)
    return rows


def load_epa():
    archive = zipfile.ZipFile(os.path.join(RAW, "epa-vehicles.csv.zip"))
    text = read_text(archive.read("vehicles.csv"))
    found = []
    for row in csv.DictReader(io.StringIO(text)):
        mapped = map_epa(row)
        if mapped is not None:
            found.append(mapped)
    print("epa", len(found), flush=True)
    return found


def load_eea_file(path):
    import json
    found = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            mapped = map_eea(json.loads(line))
            if mapped is not None:
                found.append(mapped)
    return found


def load_eea():
    folder = os.path.join(RAW, "eea")
    found = []
    if not os.path.isdir(folder):
        print("eea missing", flush=True)
        return found
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".jsonl"):
            continue
        loaded = load_eea_file(os.path.join(folder, name))
        print("eea", name, len(loaded), flush=True)
        found.extend(loaded)
    return found


COLUMNS = (
    "id", "source", "make", "model", "variant", "year", "fuel", "powertrain",
    "test_cycle", "measure_kind", "combined_unit", "combined_scale", "combined_unscaled",
    "urban_unscaled", "extra_unscaled", "city_unscaled", "highway_unscaled",
    "co2_unscaled", "co2_grams_per_mile", "power_kw", "label",
)


def create(connection):
    connection.execute("DROP TABLE IF EXISTS models")
    connection.execute("DROP TABLE IF EXISTS meta")
    connection.execute(
        """
        CREATE TABLE models (
            id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            make TEXT NOT NULL,
            model TEXT NOT NULL,
            variant TEXT NOT NULL,
            year INTEGER NOT NULL,
            fuel TEXT NOT NULL,
            powertrain TEXT NOT NULL,
            test_cycle TEXT NOT NULL,
            measure_kind TEXT NOT NULL,
            combined_unit TEXT NOT NULL,
            combined_scale INTEGER NOT NULL,
            combined_unscaled INTEGER NOT NULL,
            urban_unscaled INTEGER,
            extra_unscaled INTEGER,
            city_unscaled INTEGER,
            highway_unscaled INTEGER,
            co2_unscaled INTEGER,
            co2_grams_per_mile INTEGER NOT NULL,
            power_kw INTEGER,
            label TEXT NOT NULL
        )
        """
    )
    connection.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")


def insert_all(connection, rows):
    sql = "INSERT OR IGNORE INTO models (" + ", ".join(COLUMNS) + ") VALUES (" + ", ".join("?" for _ in COLUMNS) + ")"
    batch = []
    for row in rows:
        batch.append(tuple(row[name] for name in COLUMNS))
        if len(batch) == 1000:
            connection.executemany(sql, batch)
            batch = []
    if batch:
        connection.executemany(sql, batch)


def write_meta(connection, built_at):
    pairs = {
        "built_at": str(built_at),
        "url_ADEME": ADEME_URL,
        "protocol_ADEME": ADEME_PROTOCOL,
        "url_EPA": EPA_URL,
        "protocol_EPA": EPA_PROTOCOL,
        "url_EEA": EEA_URL,
        "protocol_EEA": EEA_PROTOCOL,
    }
    for key, value in pairs.items():
        connection.execute("INSERT INTO meta (key, value) VALUES (?, ?)", (key, value))


def verify(connection):
    alfa = connection.execute(
        """
        SELECT combined_unscaled, co2_unscaled, fuel, test_cycle, measure_kind
        FROM models
        WHERE source = 'ADEME' AND year = 2015 AND make = 'ALFA ROMEO'
          AND variant = '159 1750 Tbi (200ch)' AND combined_unscaled = 78
        """
    ).fetchone()
    if alfa is None:
        raise SystemExit("ADEME Alfa Romeo 159 2015 7.8 L/100 km row is missing")
    if alfa[1] != 182 or alfa[2] != "PETROL" or alfa[3] != "NEDC" or alfa[4] != "LIQUID":
        raise SystemExit("ADEME Alfa Romeo 159 row does not match the 2015 file")
    epa = connection.execute("SELECT COUNT(*) FROM models WHERE source = 'EPA' AND year >= 2003").fetchone()[0]
    if epa < 1000:
        raise SystemExit("EPA catalogue is unexpectedly small")
    ademe_2013 = connection.execute(
        "SELECT COUNT(*) FROM models WHERE source = 'ADEME' AND year = 2013"
    ).fetchone()[0]
    if ademe_2013 < 1000:
        raise SystemExit("ADEME 2013 catalogue is unexpectedly small")
    print("verified alfa 159,", epa, "epa rows,", ademe_2013, "ademe 2013 rows")


def main():
    built_at = int(time.time() * 1000)
    rows = []
    rows.extend(load_ademe())
    rows.extend(load_epa())
    rows.extend(load_eea())
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)
    connection = sqlite3.connect(DB_PATH)
    create(connection)
    insert_all(connection, rows)
    write_meta(connection, built_at)
    connection.execute("PRAGMA user_version = 1")
    connection.commit()
    verify(connection)
    counts = connection.execute("SELECT source, COUNT(*) FROM models GROUP BY source").fetchall()
    print("counts", counts, "bytes", os.path.getsize(DB_PATH))
    connection.execute("VACUUM")
    connection.close()
    ship_catalogue()
    print("wrote", GZ_PATH)


def ship_catalogue():
    with open(DB_PATH, "rb") as source, gzip.open(GZ_PATH, "wb", compresslevel=9) as target:
        while True:
            chunk = source.read(1024 * 1024)
            if not chunk:
                break
            target.write(chunk)
    os.remove(DB_PATH)


if __name__ == "__main__":
    main()
