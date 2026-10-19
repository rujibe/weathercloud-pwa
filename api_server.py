"""Small JSON bridge between the Weathercloud PWA and the Weathercloud API."""

from __future__ import annotations

import json
import os
import re
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from weathercloud import WeathercloudClient


DEFAULT_PORT = 8787
ALLOWED_ORIGIN = os.environ.get("PWA_ORIGIN", "https://rujibe.github.io")
STATION_ID_PATTERN = re.compile(r"^[0-9]{1,20}$")


class ApiHandler(BaseHTTPRequestHandler):
    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=120")
        self.send_header("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "86400")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)

        if parsed.path == "/api/health":
            self._send_json(200, {"ok": True})
            return

        if parsed.path != "/api/current":
            self._send_json(404, {"error": "Ruta no encontrada."})
            return

        station_id = parse_qs(parsed.query).get("station_id", [""])[0].strip()
        if not STATION_ID_PATTERN.fullmatch(station_id):
            self._send_json(400, {"error": "El ID de estación no es válido."})
            return

        try:
            with WeathercloudClient() as client:
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
            self._send_json(
                200,
                {"station_id": station_id, "readings": readings},
            )
        except Exception:
            self._send_json(
                502,
                {"error": "Weathercloud no ha podido devolver los datos de esta estación."},
            )

    def log_message(self, format: str, *args: object) -> None:
        print("API:", format % args)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", str(DEFAULT_PORT)))
    host = os.environ.get(
        "HOST", "0.0.0.0" if "PORT" in os.environ else "127.0.0.1"
    )
    server = ThreadingHTTPServer((host, port), ApiHandler)
    print(f"Puente de Weathercloud activo en http://{host}:{port}")
    server.serve_forever()
