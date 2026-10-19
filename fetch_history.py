"""Fetch hourly history for the configured Weathercloud stations."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

from weathercloud import VariableCode, WeathercloudClient


ROOT = Path(__file__).resolve().parent
STATION_ID_PATTERN = re.compile(r"^[0-9]{1,20}$")
VARIABLES = {
    "temp": VariableCode.TEMPERATURE,
    "hum": VariableCode.HUMIDITY,
    "dew": VariableCode.DEW_POINT,
    "bar": VariableCode.PRESSURE,
    "wspd": VariableCode.WIND_SPEED,
    "wdir": VariableCode.WIND_DIRECTION,
    "gust": VariableCode.WIND_GUST,
    "rain": VariableCode.RAIN,
    "rainrate": VariableCode.RAIN_RATE,
    "solar": VariableCode.SOLAR_RADIATION,
    "uv": VariableCode.UV_INDEX,
}


def main() -> None:
    config = json.loads((ROOT / "stations.json").read_text(encoding="utf-8"))
    previous_path = ROOT / "history.json"
    previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.exists() else {}
    output = {
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "stations": {},
    }

    with WeathercloudClient() as client:
        for station in config.get("stations", []):
            station_id = str(station.get("id", "")).strip()
            if not STATION_ID_PATTERN.fullmatch(station_id):
                continue

            old = previous.get("stations", {}).get(station_id, {})
            series = dict(old.get("series", {}))
            errors = {}
            for key, variable in VARIABLES.items():
                try:
                    response = client.get_evolution(station_id, variable, "year")
                    data = response.get("data", response)
                    points = []
                    for timestamp, buckets in (data.get("values") or {}).items():
                        item = buckets.get(str(int(variable)), {}) if isinstance(buckets, dict) else {}
                        stats = item.get("stats", {}) if isinstance(item, dict) else {}
                        samples = stats.get("samples") or item.get("samples") or 0
                        total = stats.get("sum")
                        if total is None:
                            continue
                        value = float(total) / max(int(samples), 1)
                        points.append({"time": int(timestamp), "value": value})
                    if points:
                        series[key] = sorted(points, key=lambda point: point["time"])
                    elif key not in series:
                        errors[key] = "Weathercloud no devolvió puntos para este sensor."
                except Exception:
                    if key not in series:
                        errors[key] = "No se pudo consultar el histórico de este sensor."

            if not series:
                raise RuntimeError(f"No se recibió histórico para la estación {station_id}.")
            output["stations"][station_id] = {"series": series, "errors": errors}

    if not output["stations"]:
        raise RuntimeError("No hay estaciones válidas en stations.json.")
    previous_path.write_text(
        json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
