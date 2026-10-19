"""Fetch real monthly Weathercloud evolution data for configured stations."""

from __future__ import annotations

import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path

from weathercloud import WeathercloudClient


ROOT = Path(__file__).resolve().parent
STATION_ID_PATTERN = re.compile(r"^[0-9]{1,20}$")

# IDs confirmed against the evolution graphs on the Weathercloud station page.
# Some graphs contain multiple series in one response (wind speed/gust and rain/rain rate).
SERIES = {
    "temp": {"request": 101, "code": 101, "mode": "average"},
    "hum": {"request": 201, "code": 201, "mode": "average"},
    "bar": {"request": 701, "code": 701, "mode": "average"},
    "wspd": {"request": 541, "code": 541, "mode": "average"},
    "gust": {"request": 541, "code": 521, "mode": "maximum"},
    "wdir": {"request": 641, "code": 641, "mode": "direction"},
    "rain": {"request": 801, "code": 801, "mode": "total"},
    "rainrate": {"request": 801, "code": 901, "mode": "maximum"},
    "solar": {"request": 1001, "code": 1001, "mode": "average"},
    "uv": {"request": 1101, "code": 1101, "mode": "average"},
    "et": {"request": 811, "code": 811, "mode": "total"},
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


def bucket_value(bucket: object, mode: str) -> float | None:
    if not isinstance(bucket, dict):
        return None
    stats = bucket.get("stats") if isinstance(bucket.get("stats"), dict) else bucket

    if mode == "direction":
        vector = stats.get("sum")
        if not isinstance(vector, dict):
            return None
        try:
            x, y = float(vector["x"]), float(vector["y"])
        except (KeyError, TypeError, ValueError):
            return None
        if x == 0 and y == 0:
            return None
        return (math.degrees(math.atan2(y, x)) + 360) % 360

    if mode == "maximum":
        value = stats.get("max")
        try:
            return float(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    if mode == "total":
        value = stats.get("total")
        if value is None:
            value = stats.get("sum")
        try:
            return float(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    total = stats.get("sum")
    samples = bucket.get("samples") or stats.get("samples")
    if total is not None and samples:
        try:
            return float(total) / int(samples)
        except (TypeError, ValueError, ZeroDivisionError):
            pass
    for field in ("average", "avg", "mean", "value"):
        if stats.get(field) is not None:
            try:
                return float(stats[field])
            except (TypeError, ValueError):
                pass
    return None


def extract_points(response: dict, code: int, mode: str) -> list[dict[str, float | int]]:
    data = response.get("data", response)
    values = data.get("values", {}) if isinstance(data, dict) else {}
    if not isinstance(values, dict):
        return []

    code_text = str(code)
    points: dict[int, float] = {}
    for outer_key, outer_value in values.items():
        timestamp = unix_timestamp(outer_key)
        if isinstance(outer_value, dict) and timestamp is not None:
            value = bucket_value(outer_value.get(code_text), mode)
            if value is not None:
                points[timestamp] = value
            continue

        # Accept the alternate code -> timestamp -> bucket response shape too.
        if str(outer_key) == code_text and isinstance(outer_value, dict):
            for inner_key, bucket in outer_value.items():
                timestamp = unix_timestamp(inner_key)
                value = bucket_value(bucket, mode)
                if timestamp is not None and value is not None:
                    points[timestamp] = value

    return [{"time": timestamp, "value": value} for timestamp, value in sorted(points.items())]


def main() -> None:
    config = json.loads((ROOT / "stations.json").read_text(encoding="utf-8"))
    output = {
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "stations": {},
    }

    with WeathercloudClient() as client:
        for station in config.get("stations", []):
            station_id = str(station.get("id", "")).strip()
            if not STATION_ID_PATTERN.fullmatch(station_id):
                continue

            series = {}
            errors = {}
            by_request: dict[int, list[tuple[str, dict]]] = {}
            for key, definition in SERIES.items():
                by_request.setdefault(definition["request"], []).append((key, definition))

            for request_code, definitions in by_request.items():
                try:
                    response = client.get_evolution(station_id, request_code, "month")
                    for key, definition in definitions:
                        points = extract_points(response, definition["code"], definition["mode"])
                        if points:
                            series[key] = points
                            print(f"{station_id} {key}: {len(points)} puntos del último mes")
                        else:
                            errors[key] = "Weathercloud no devolvió datos para este sensor."
                            print(f"{station_id} {key}: sin datos históricos")
                except Exception as exc:
                    for key, _ in definitions:
                        errors[key] = "No se pudo consultar el histórico de este sensor."
                    print(f"{station_id} variable {request_code}: {type(exc).__name__}: {exc}")

            if not series:
                raise RuntimeError(f"No se recibió histórico para la estación {station_id}.")
            output["stations"][station_id] = {"series": series, "errors": errors}

    if not output["stations"]:
        raise RuntimeError("No hay estaciones válidas en stations.json.")
    (ROOT / "history.json").write_text(
        json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
