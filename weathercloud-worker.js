const ALLOWED_ORIGINS = new Set([
  "https://rujibe.github.io",
  "http://127.0.0.1:8787",
  "http://localhost:8787",
]);

const SERIES = {
  temp: { request: 101, code: "101", mode: "average" },
  hum: { request: 201, code: "201", mode: "average" },
  bar: { request: 701, code: "701", mode: "average" },
  wspd: { request: 541, code: "541", mode: "average" },
  wdir: { request: 641, code: "641", mode: "direction" },
  gust: { request: 541, code: "521", mode: "maximum" },
  rain: { request: 801, code: "801", mode: "total" },
  rainrate: { request: 801, code: "901", mode: "maximum" },
  solar: { request: 1001, code: "1001", mode: "average" },
  uv: { request: 1101, code: "1101", mode: "average" },
  et: { request: 811, code: "811", mode: "total" },
};

function corsHeaders(origin) {
  const headers = new Headers({
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  });
  if (ALLOWED_ORIGINS.has(origin)) headers.set("Access-Control-Allow-Origin", origin);
  return headers;
}

function jsonResponse(body, status, origin, cacheControl = "no-store") {
  const headers = corsHeaders(origin);
  headers.set("Content-Type", "application/json; charset=utf-8");
  headers.set("Cache-Control", cacheControl);
  return new Response(JSON.stringify(body), { status, headers });
}

function getBucketValue(bucket, mode) {
  if (!bucket || typeof bucket !== "object") return null;
  const stats = bucket.stats && typeof bucket.stats === "object" ? bucket.stats : bucket;

  if (mode === "direction") {
    const vector = stats.sum;
    if (!vector || typeof vector !== "object") return null;
    const x = Number(vector.x), y = Number(vector.y);
    if (!Number.isFinite(x) || !Number.isFinite(y) || (x === 0 && y === 0)) return null;
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }

  if (mode === "maximum") {
    if (stats.max == null) return null;
    const value = Number(stats.max);
    return Number.isFinite(value) ? value : null;
  }
  if (mode === "total") {
    const rawValue = stats.total ?? stats.sum;
    if (rawValue == null) return null;
    const value = Number(rawValue);
    return Number.isFinite(value) ? value : null;
  }

  const sum = stats.sum == null ? NaN : Number(stats.sum);
  const samples = Number(bucket.samples ?? stats.samples);
  if (Number.isFinite(sum) && Number.isFinite(samples) && samples > 0) return sum / samples;
  for (const name of ["average", "avg", "mean", "value"]) {
    if (stats[name] == null) continue;
    const value = Number(stats[name]);
    if (Number.isFinite(value)) return value;
  }
  return null;
}

function extractPoints(payload, code, mode) {
  const values = payload?.data?.values ?? payload?.values ?? {};
  if (!values || typeof values !== "object" || Array.isArray(values)) return [];
  const points = [];
  for (const [timestampText, row] of Object.entries(values)) {
    const time = Number(timestampText);
    const bucket = row?.[code];
    const value = getBucketValue(bucket, mode);
    if (Number.isFinite(time) && value !== null) {
      const stats = bucket?.stats && typeof bucket.stats === "object" ? bucket.stats : bucket;
      const point = { time, value };
      const minimum = stats?.min == null ? null : Number(stats.min);
      const maximum = stats?.max == null ? null : Number(stats.max);
      if (Number.isFinite(minimum)) point.min = minimum;
      if (Number.isFinite(maximum)) point.max = maximum;
      points.push(point);
    }
  }
  return points.sort((a, b) => a.time - b.time);
}

async function cachedJson(request, context, ttl, makeUpstreamRequest) {
  const cache = caches.default;
  const cacheKey = new Request(request.url, { method: "GET" });
  const saved = await cache.match(cacheKey);
  if (saved) return saved;

  const upstream = await makeUpstreamRequest();
  const text = await upstream.text();
  if (!upstream.ok) {
    return new Response(JSON.stringify({ error: `Weathercloud respondió ${upstream.status}.` }), {
      status: upstream.status,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    return new Response(JSON.stringify({ error: "Weathercloud devolvió una respuesta no válida." }), {
      status: 502,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
    });
  }
  const response = new Response(JSON.stringify(data), {
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": `public, max-age=${ttl}` },
  });
  context.waitUntil(cache.put(cacheKey, response.clone()));
  return response;
}

export default {
  async fetch(request, env, context) {
    const origin = request.headers.get("Origin") || "";
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(origin) });
    if (request.method !== "GET") return jsonResponse({ error: "Usa una consulta GET." }, 405, origin);
    if (origin && !ALLOWED_ORIGINS.has(origin)) return jsonResponse({ error: "Origen no permitido." }, 403, origin);
    if (url.pathname === "/health") return jsonResponse({ ok: true }, 200, origin, "public, max-age=60");

    if (url.pathname === "/api/nearby") {
      const latitudeText = url.searchParams.get("latitude");
      const longitudeText = url.searchParams.get("longitude");
      const latitude = Number(latitudeText);
      const longitude = Number(longitudeText);
      const distanceKm = Number(url.searchParams.get("distance_km") || "10");
      if (latitudeText === null || longitudeText === null || latitudeText === "" || longitudeText === "" || !Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
        return jsonResponse({ error: "La ubicación no es válida." }, 400, origin);
      }
      if (!Number.isInteger(distanceKm) || distanceKm < 1 || distanceKm > 100) {
        return jsonResponse({ error: "El radio debe estar entre 1 y 100 km." }, 400, origin);
      }
      const response = await cachedJson(request, context, 180, () => fetch(
        `https://app.weathercloud.net/page/coordinates/latitude/${latitude}/longitude/${longitude}/distance/${distanceKm}`,
        { headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" } },
      ));
      const headers = corsHeaders(origin);
      for (const [name, value] of response.headers) headers.set(name, value);
      return new Response(response.body, { status: response.status, headers });
    }

    const stationId = url.searchParams.get("station_id") || "";
    if (!/^\d{1,20}$/.test(stationId)) return jsonResponse({ error: "El ID de estación no es válido." }, 400, origin);

    if (url.pathname === "/api/current") {
      const upstreamUrl = new URL("https://app.weathercloud.net/device/values");
      upstreamUrl.searchParams.set("code", stationId);
      // Expire before the PWA's five-minute polling interval so each cycle can reach Weathercloud.
      const response = await cachedJson(request, context, 240, () => fetch(upstreamUrl, {
        headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" },
      }));
      const headers = corsHeaders(origin);
      for (const [name, value] of response.headers) headers.set(name, value);
      return new Response(response.body, { status: response.status, headers });
    }

    if (url.pathname === "/api/history") {
      const key = url.searchParams.get("variable") || "temp";
      const period = url.searchParams.get("period") || "month";
      const config = SERIES[key];
      if (!config) return jsonResponse({ error: "Ese histórico no está disponible." }, 400, origin);
      if (!["day", "week", "month"].includes(period)) {
        return jsonResponse({ error: "El periodo debe ser 24 horas, 7 días o 30 días." }, 400, origin);
      }

      const cacheUrl = new URL(request.url);
      cacheUrl.searchParams.sort();
      const cachedRequest = new Request(cacheUrl, { method: "GET" });
      const cache = caches.default;
      let response = await cache.match(cachedRequest);
      if (!response) {
        const body = new URLSearchParams({ device: stationId, variable: String(config.request), period });
        const upstream = await fetch("https://app.weathercloud.net/device/evolution", {
          method: "POST",
          headers: {
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With": "XMLHttpRequest",
          },
          body,
        });
        if (!upstream.ok) return jsonResponse({ error: `Weathercloud respondió ${upstream.status}.` }, upstream.status, origin);
        let payload;
        try {
          payload = await upstream.json();
        } catch {
          return jsonResponse({ error: "Weathercloud devolvió una respuesta no válida." }, 502, origin);
        }
        const result = {
          station_id: stationId,
          variable: key,
          period,
          updated_at: new Date().toISOString(),
          points: extractPoints(payload, config.code, config.mode),
        };
        const ttl = period === "day" ? 240 : period === "week" ? 900 : 3600;
        response = new Response(JSON.stringify(result), {
          headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": `public, max-age=${ttl}` },
        });
        context.waitUntil(cache.put(cachedRequest, response.clone()));
      }
      const headers = corsHeaders(origin);
      for (const [name, value] of response.headers) headers.set(name, value);
      return new Response(response.body, { status: response.status, headers });
    }

    return jsonResponse({ error: "Ruta no encontrada." }, 404, origin);
  },
};
