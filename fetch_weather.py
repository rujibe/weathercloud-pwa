"""Fetch configured Weathercloud stations into a static JSON file for GitHub Pages."""

from __future__ import annotations

import json
import re
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from weathercloud import WeathercloudClient


ROOT = Path(__file__).resolve().parent
STATION_ID_PATTERN = re.compile(r"^[0-9]{1,20}$")


def main() -> None:
    configuration = json.loads((ROOT / "stations.json").read_text(encoding="utf-8"))
    result = {
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "stations": {},
    }

    with WeathercloudClient() as client:
        for station in configuration.get("stations", []):
            station_id = str(station.get("id", "")).strip()
            if not STATION_ID_PATTERN.fullmatch(station_id):
                continue

            try:
                values = asdict(client.get_current_conditions(station_id))
                readings = {
                    "epoch": values["epoch"],
                    "temp": values["temperature"],
                    "dew": values["dew_point"],
                    "hum": values["humidity"],
                    "bar": values["pressure"],
                    "wspd": values["wind_speed"],
                    "rain": values["rain"],
                    "uvi": values["uv_index"],
                }
                result["stations"][station_id] = {"readings": readings}
            except Exception:
                result["stations"][station_id] = {
                    "error": "Weathercloud no ha podido devolver los datos de esta estación."
                }

    (ROOT / "weather.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
