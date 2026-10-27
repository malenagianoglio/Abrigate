// abrigate-api: proxy de OpenWeather con caché, pensado para correr en Cloud Run.
//
// Por qué existe: el plan gratuito de OpenWeather tiene un tope de 1.000 llamadas
// diarias POR CUENTA. Si cada celular le pega directo, el tope se agota con pocos
// usuarios. Este servicio agrupa a los usuarios cercanos y le pega a OpenWeather
// una sola vez cada 30 minutos por zona.

const http = require("node:http");
const os = require("node:os");
const { randomUUID } = require("node:crypto");

const PORT = Number(process.env.PORT) || 8080;
const API_KEY = process.env.OPENWEATHER_API_KEY;
const TTL_MS = 30 * 60 * 1000;   // 30 minutos
const MAX_ENTRADAS = 5000;       // tope de memoria de la caché

// Identifica a la instancia que respondió. Sirve para ver el balanceo de carga
// en la prueba: si hay varias instancias, aparecen ids distintos.
// En Cloud Run todas las instancias se llaman "localhost" y suelen tener el mismo
// PID, así que se agrega un sufijo aleatorio para distinguirlas.
const INSTANCIA = `${os.hostname()}-${process.pid}-${randomUUID().slice(0, 8)}`;

// clave de zona -> { datos, vence }
const cache = new Map();
// clave de zona -> Promise en curso (si 50 usuarios piden la misma zona a la vez
// y no está en caché, se hace UNA sola llamada a OpenWeather y todos la esperan)
const enCurso = new Map();

let stats = { pedidos: 0, hits: 0, misses: 0, errores: 0, llamadasOpenWeather: 0 };

// Redondear a 2 decimales agrupa coordenadas en celdas de ~1 km.
// Es la misma precisión que usa OpenWeather internamente.
function claveZona(lat, lon) {
  return `${lat.toFixed(2)},${lon.toFixed(2)}`;
}

async function pedirAOpenWeather(lat, lon) {
  const url = new URL("https://api.openweathermap.org/data/2.5/weather");
  url.search = new URLSearchParams({
    lat: lat.toFixed(2),
    lon: lon.toFixed(2),
    appid: API_KEY,
    units: "metric",
    lang: "es",
  });
  stats.llamadasOpenWeather++;
  const resp = await fetch(url, { signal: AbortSignal.timeout(8000) });
  if (!resp.ok) throw new Error(`OpenWeather respondió ${resp.status}`);
  return resp.json();
}

async function obtenerClima(lat, lon) {
  const clave = claveZona(lat, lon);
  const ahora = Date.now();

  const guardado = cache.get(clave);
  if (guardado && guardado.vence > ahora) {
    stats.hits++;
    return { datos: guardado.datos, cache: "HIT" };
  }

  stats.misses++;
  if (!enCurso.has(clave)) {
    const promesa = pedirAOpenWeather(lat, lon)
      .then((datos) => {
        if (cache.size >= MAX_ENTRADAS) {
          // Map conserva el orden de inserción: el primero es el más viejo
          cache.delete(cache.keys().next().value);
        }
        cache.set(clave, { datos, vence: Date.now() + TTL_MS });
        return datos;
      })
      .finally(() => enCurso.delete(clave));
    enCurso.set(clave, promesa);
  }
  return { datos: await enCurso.get(clave), cache: "MISS" };
}

function responder(res, status, cuerpo, extra = {}) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "X-Instancia": INSTANCIA,
    ...extra,
  });
  res.end(JSON.stringify(cuerpo));
}

const servidor = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === "/salud") {
    return responder(res, 200, {
      estado: "ok",
      instancia: INSTANCIA,
      entradasEnCache: cache.size,
      ...stats,
    });
  }

  if (url.pathname !== "/clima" || req.method !== "GET") {
    return responder(res, 404, { error: "Ruta no encontrada" });
  }

  stats.pedidos++;
  const lat = Number(url.searchParams.get("lat"));
  const lon = Number(url.searchParams.get("lon"));
  const validas =
    url.searchParams.has("lat") && url.searchParams.has("lon") &&
    Number.isFinite(lat) && Number.isFinite(lon) &&
    Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
  if (!validas) {
    return responder(res, 400, { error: "lat y lon son obligatorios y deben ser coordenadas válidas" });
  }

  try {
    const { datos, cache: estado } = await obtenerClima(lat, lon);
    responder(res, 200, datos, { "X-Cache": estado });
  } catch (e) {
    stats.errores++;
    // Log estructurado: Cloud Logging lo interpreta como entrada de severidad ERROR
    console.error(JSON.stringify({ severity: "ERROR", message: e.message, lat, lon }));
    responder(res, 502, { error: "No pudimos obtener el clima" });
  }
});

if (!API_KEY) {
  console.error(JSON.stringify({ severity: "ERROR", message: "Falta OPENWEATHER_API_KEY" }));
  process.exit(1);
}

servidor.listen(PORT, () => {
  console.log(JSON.stringify({ severity: "INFO", message: `abrigate-api escuchando en ${PORT}`, instancia: INSTANCIA }));
});

// Cloud Run manda SIGTERM antes de apagar una instancia (por ejemplo, al bajar
// la cantidad de instancias). Cerramos prolijo para no cortar pedidos en curso.
process.on("SIGTERM", () => servidor.close(() => process.exit(0)));
