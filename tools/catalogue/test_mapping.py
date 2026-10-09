#!/usr/bin/env python3
"""Mapping tests use published field names and do not invent a figure."""

import unittest

from build_catalogue import map_ademe, map_eea, map_epa


class AdemeTests(unittest.TestCase):
    def test_essence_nedc_litres_and_grams(self):
        row = map_ademe({
            "lib mrq doss": "ALFA ROMEO",
            "lib mod doss": "159",
            "dscom": "159 1750 Tbi (200ch)",
            "energ": "ES ",
            "hybride": "non ",
            "conso mixte": "7.8",
            "conso urb": "11.3",
            "conso exurb": "5.8",
            "co2 mixte": "182",
            "cnit": "M10ALFVP000G340",
        }, 2015)
        self.assertEqual(row["fuel"], "PETROL")
        self.assertEqual(row["powertrain"], "ICE")
        self.assertEqual(row["measure_kind"], "LIQUID")
        self.assertEqual(row["test_cycle"], "NEDC")
        self.assertEqual(row["combined_unit"], "L_PER_100KM")
        self.assertEqual(row["combined_scale"], 1)
        self.assertEqual(row["combined_unscaled"], 78)
        self.assertEqual(row["urban_unscaled"], 113)
        self.assertEqual(row["extra_unscaled"], 58)
        self.assertEqual(row["co2_unscaled"], 182)
        self.assertEqual(row["co2_grams_per_mile"], 0)

    def test_float_noise_rounds_half_up_to_the_label(self):
        row = map_ademe({
            "marque": "ALFA-ROMEO",
            "modele": "159",
            "cod cbr": "ES",
            "conso mixte": "7,800000191",
            "co2": "182",
        }, 2014)
        self.assertEqual(row["combined_unscaled"], 78)

    def test_bifuel_rows_keep_the_fuel_the_figure_describes(self):
        petrol = map_ademe({"marque": "ALFA", "modele": "GIULIETTA", "energ": "ES/GP", "mixte": "6.4", "co2": "148"}, 2015)
        lpg = map_ademe({"marque": "ALFA", "modele": "GIULIETTA", "energ": "GP/ES", "mixte": "8.3", "co2": "134"}, 2015)
        self.assertEqual(petrol["fuel"], "PETROL")
        self.assertEqual(lpg["fuel"], "LPG")
        self.assertEqual(lpg["combined_unscaled"], 83)

    def test_plugin_is_weighted_and_gas_is_skipped(self):
        plugin = map_ademe({"marque": "AUDI", "modele": "A3", "energ": "EE", "hybride": "oui", "mixte": "1.5", "co2": "35"}, 2015)
        self.assertEqual(plugin["powertrain"], "PHEV")
        self.assertEqual(plugin["measure_kind"], "WEIGHTED")
        self.assertIsNone(map_ademe({"marque": "M", "modele": "B", "energ": "GN", "mixte": "6.6", "co2": "117"}, 2015))
        self.assertIsNone(map_ademe({"marque": "B", "modele": "I3", "energ": "EL", "mixte": "", "co2": ""}, 2015))
        self.assertIsNone(map_ademe({"marque": "J", "modele": "GC", "energ": "FE", "mixte": "13.8", "co2": "225"}, 2015))

    def test_hybrid_flag_does_not_override_a_plugin_code(self):
        hybrid = map_ademe({"marque": "BMW", "modele": "AH3", "energ": "EH", "hybride": "oui", "mixte": "5.9", "co2": "139"}, 2015)
        self.assertEqual(hybrid["powertrain"], "HYBRID")
        self.assertEqual(hybrid["measure_kind"], "LIQUID")


class EpaTests(unittest.TestCase):
    def test_gasoline_mpg_and_grams_per_mile(self):
        row = map_epa({
            "id": "1", "year": "2010", "make": "Toyota", "model": "Corolla",
            "fuelType1": "Regular Gasoline", "atvType": "", "comb08": "30",
            "city08": "26", "highway08": "35", "co2TailpipeGpm": "296.4",
            "displ": "1.8", "trany": "Automatic",
        })
        self.assertEqual(row["fuel"], "PETROL")
        self.assertEqual(row["combined_unit"], "US_MPG")
        self.assertEqual(row["combined_unscaled"], 30)
        self.assertEqual(row["city_unscaled"], 26)
        self.assertEqual(row["co2_unscaled"], 296)
        self.assertEqual(row["co2_grams_per_mile"], 1)
        self.assertEqual(row["test_cycle"], "EPA")
        self.assertEqual(row["variant"], "1.8 L · Automatic")

    def test_plugin_keeps_gasoline_mpg_as_charge_sustaining(self):
        row = map_epa({
            "id": "2", "year": "2011", "make": "Chevrolet", "model": "Volt",
            "fuelType1": "Premium Gasoline", "atvType": "Plug-in Hybrid",
            "comb08": "37", "co2TailpipeGpm": "84.0",
        })
        self.assertEqual(row["powertrain"], "PHEV")
        self.assertEqual(row["measure_kind"], "CHARGE_SUSTAINING")
        self.assertEqual(row["combined_unscaled"], 37)

    def test_skips_electric_natural_gas_and_old_years(self):
        self.assertIsNone(map_epa({"id": "3", "year": "2022", "make": "T", "model": "E", "fuelType1": "Electricity", "atvType": "EV", "comb08": "0"}))
        self.assertIsNone(map_epa({"id": "4", "year": "2003", "make": "Ford", "model": "F150 CNG", "fuelType1": "Natural Gas", "atvType": "CNG", "comb08": "13"}))
        self.assertIsNone(map_epa({"id": "5", "year": "2002", "make": "Ford", "model": "Focus", "fuelType1": "Regular Gasoline", "atvType": "", "comb08": "28"}))


class EeaTests(unittest.TestCase):
    def test_wltp_litres_for_a_monofuel(self):
        row = map_eea({"Mk": "FIAT", "Cn": "PANDA", "Ft": "lpg", "Fm": "B", "ec": 1242, "ep": 51, "co2": 119, "Fc": 6.0, "year": 2024})
        self.assertEqual(row["fuel"], "LPG")
        self.assertEqual(row["powertrain"], "ICE")
        self.assertEqual(row["test_cycle"], "WLTP")
        self.assertEqual(row["combined_unscaled"], 60)
        self.assertEqual(row["co2_unscaled"], 119)
        self.assertEqual(row["co2_grams_per_mile"], 0)
        self.assertEqual(row["year"], 2024)
        self.assertEqual(row["variant"], "1242 cm3 · 51 kW")

    def test_plugin_is_weighted_and_gas_is_skipped(self):
        plugin = map_eea({"Mk": "TOYOTA", "Cn": "C-HR", "Ft": "petrol/electric", "Fm": "P", "Fc": 1.2, "co2": 28, "year": 2024, "ec": 1987, "ep": 112})
        self.assertEqual(plugin["fuel"], "PETROL")
        self.assertEqual(plugin["powertrain"], "PHEV")
        self.assertEqual(plugin["measure_kind"], "WEIGHTED")
        self.assertEqual(plugin["variant"], "1987 cm3 · 112 kW")
        self.assertIsNone(map_eea({"Mk": "SKODA", "Cn": "KAMIQ", "Ft": "ng", "Fm": "M", "Fc": 5.6, "co2": 101, "year": 2024}))
        hybrid = map_eea({"Mk": "TOYOTA", "Cn": "YARIS", "Ft": "petrol", "Fm": "H", "Fc": 4.1, "co2": 92, "year": 2024})
        self.assertEqual(hybrid["powertrain"], "HYBRID")
        self.assertEqual(hybrid["measure_kind"], "LIQUID")


if __name__ == "__main__":
    unittest.main()
