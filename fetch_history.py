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


def unix_timestamp(value: object) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        if isinstance(value, str):
            try:
                return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())
            except ValueError:
                return None
        return None


def average_from_bucket(bucket: object) -> float | None:
    if not isinstance(bucket, dict):
        return None
    stats = bucket.get("stats") if isinstance(bucket.get("stats"), dict) else bucket
    total = stats.get("sum")
    samples = bucket.get("samples") or stats.get("samples") or 0
    if total is not None:
        try:
            return float(total) / max(int(samples), 1)
        except (TypeError, ValueError):
            pass
    for field in ("average", "avg", "mean", "value"):
        if stats.get(field) is not None:
            try:
                return float(stats[field])
            except (TypeError, ValueError):
                pass
    low, high = stats.get("min"), stats.get("max")
    if low is not None and high is not None:
        try:
            return (float(low) + float(high)) / 2
        except (TypeError, ValueError):
            return None
    return None


def extract_points(response: dict, variable: VariableCode) -> list[dict[str, float | int]]:
    data = response.get("data", response)
    values = data.get("values", {}) if isinstance(data, dict) else {}
    if not isinstance(values, dict):
        return []

    code = str(int(variable))
    points: dict[int, float] = {}
    for outer_key, outer_value in values.items():
        # Documented response: timestamp -> variable code -> {samples, stats}.
        timestamp = unix_timestamp(outer_key)
        if isinstance(outer_value, dict) and timestamp is not None:
            bucket = outer_value.get(code)
            if bucket is None and "stats" in outer_value:
                bucket = outer_value  # Some responses omit the variable-code layer.
            value = average_from_bucket(bucket)
            if value is not None:
                points[timestamp] = value
            continue

        # Also accept variable code -> timestamp -> {samples, stats}.
        if str(outer_key) == code and isinstance(outer_value, dict):
            for inner_key, bucket in outer_value.items():
                timestamp = unix_timestamp(inner_key)
                value = average_from_bucket(bucket)
                if timestamp is not None and value is not None:
                    points[timestamp] = value

    return [{"time": timestamp, "value": value} for timestamp, value in sorted(points.items())]


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
                    points = extract_points(response, variable)
                    if points:
                        series[key] = sorted(points, key=lambda point: point["time"])
                        print(f"{station_id} {key}: {len(points)} puntos históricos")
                    elif key not in series:
                        errors[key] = "Weathercloud no devolvió puntos para este sensor."
                        print(f"{station_id} {key}: sin puntos. Respuesta: {str(response)[:500]}")
                except Exception as exc:
                    errors[key] = "No se pudo consultar el histórico de este sensor."
                    print(f"{station_id} {key}: {type(exc).__name__}: {exc}")

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
