// TP6: pruebas de las alertas por zona (alertas.js) y de la ruta POST /alertas.

const test = require("node:test");
const assert = require("node:assert/strict");
const { crearAlertas, validarZona, resumirPronostico, evaluar } = require("../alertas");
const { crearServidor } = require("../server");
const { validarPerfil, crearPrompt } = require("../recomendacion");

// 19/11/2026 20:00 en Argentina (UTC-3) = 23:00 UTC
const AHORA = Date.parse("2026-11-19T23:00:00Z");
const ARG = -3 * 3600;

// Arma un pronóstico de OpenWeather con la máxima indicada para hoy y mañana.
function pronostico({ hoy, manana, lluviaManana = 0 }) {
  const bloque = (iso, temp, mm = 0) => ({
    dt: Date.parse(iso) / 1000,
    main: { temp, temp_max: temp },
    weather: [{ id: mm ? 501 : 800 }],
    ...(mm && { rain: { "3h": mm } }),
  });
  return {
    city: { timezone: ARG },
    list: [
      bloque("2026-11-20T00:00:00Z", hoy),                 // 21 h de hoy (hora local)
      bloque("2026-11-20T15:00:00Z", manana - 2),          // 12 h de mañana
      bloque("2026-11-20T18:00:00Z", manana, lluviaManana), // 15 h de mañana
      bloque("2026-11-21T06:00:00Z", 5),                   // pasado mañana: se ignora
    ],
  };
}

function storeFalso(zonas) {
  const datos = new Map(zonas.map((z) => [z.id, { ...z }]));
  return {
    datos,
    registros: [],
    async registrar(zona, t) { this.registros.push(zona); datos.set(zona, { id: zona, ...datos.get(zona), ultimaConsulta: t }); },
    async activas(desde) { return [...datos.values()].filter((z) => z.ultimaConsulta >= desde); },
    async actualizar(zona, cambios) { datos.set(zona, { ...datos.get(zona), ...cambios }); },
  };
}

function mensajeriaFalsa() {
  const enviados = [];
  return { enviados, send: async (m) => { enviados.push(m); return "id"; } };
}

test("validarZona acepta celdas de un decimal y rechaza el resto", () => {
  assert.equal(validarZona("-31.4_-62.1"), "-31.4_-62.1");
  assert.equal(validarZona("40.7_-74.0"), "40.7_-74.0");
  assert.equal(validarZona("-31.42_-62.08"), null);    // demasiado precisa
  assert.equal(validarZona("-91.0_0.0"), null);
  assert.equal(validarZona("zona_-31.4_-62.1"), null);
  assert.equal(validarZona(undefined), null);
});

test("resumirPronostico separa hoy y mañana en la hora local de la zona", () => {
  const r = resumirPronostico(pronostico({ hoy: 30, manana: 18, lluviaManana: 12 }), AHORA);
  assert.equal(r.hoy.fecha, "2026-11-19");
  assert.equal(r.hoy.max, 30);
  assert.equal(r.manana.fecha, "2026-11-20");
  assert.equal(r.manana.max, 18);
  assert.equal(r.manana.lluviaFuerte, true);
});

test("evaluar avisa con 8 °C de diferencia o lluvia fuerte, y no en otro caso", () => {
  assert.equal(evaluar({ maxHoy: 32, manana: { max: 20, lluviaFuerte: false } }).titulo, "Mañana bajan 12 °C");
  assert.equal(evaluar({ maxHoy: 10, manana: { max: 19, lluviaFuerte: false } }).titulo, "Mañana suben 9 °C");
  assert.equal(evaluar({ maxHoy: 25, manana: { max: 22, lluviaFuerte: true } }).titulo, "Mañana se espera lluvia fuerte");
  assert.equal(evaluar({ maxHoy: 25, manana: { max: 20, lluviaFuerte: false } }), null);
});

test("enviar manda una push al tema de la zona y no repite el mismo día", async () => {
  const store = storeFalso([
    { id: "-31.4_-62.1", ultimaConsulta: AHORA - 86400000, maxManana: 31, fechaMaxManana: "2026-11-19" },
  ]);
  const mensajeria = mensajeriaFalsa();
  const alertas = crearAlertas({
    store, mensajeria, ahora: () => AHORA,
    pronostico: async () => pronostico({ hoy: 29, manana: 19 }),
  });

  assert.deepEqual(await alertas.enviar(), { zonas: 1, enviadas: 1, errores: 0 });
  const m = mensajeria.enviados[0];
  assert.equal(m.topic, "zona_-31.4_-62.1");
  assert.equal(m.notification.title, "Mañana bajan 12 °C");   // usa la máxima guardada ayer (31)
  assert.equal(m.android.notification.channelId, "alertas_clima");
  assert.equal(m.data.destino, "recomendacion");
  assert.equal(store.datos.get("-31.4_-62.1").alertaEnviada, "2026-11-19");
  assert.equal(store.datos.get("-31.4_-62.1").maxManana, 19);

  await alertas.enviar();
  assert.equal(mensajeria.enviados.length, 1);                // máximo 1 por zona y por día
});

test("enviar ignora zonas inactivas y cambios chicos, y sigue si una zona falla", async () => {
  const store = storeFalso([
    { id: "-31.4_-62.1", ultimaConsulta: AHORA - 20 * 86400000 },   // inactiva hace 20 días
    { id: "-34.6_-58.4", ultimaConsulta: AHORA },                    // cambio chico
    { id: "-32.9_-60.7", ultimaConsulta: AHORA },                    // falla OpenWeather
  ]);
  const mensajeria = mensajeriaFalsa();
  const alertas = crearAlertas({
    store, mensajeria, ahora: () => AHORA,
    pronostico: async ({ lat }) => {
      if (lat === -32.9) throw new Error("OpenWeather caído");
      return pronostico({ hoy: 24, manana: 21 });
    },
  });
  assert.deepEqual(await alertas.enviar(), { zonas: 2, enviadas: 0, errores: 1 });
  assert.equal(store.datos.get("-34.6_-58.4").maxManana, 21);       // se guarda para mañana
});

test("la primera noche de una zona no compara temperaturas: solo guarda la máxima de mañana", async () => {
  const store = storeFalso([{ id: "-31.4_-62.1", ultimaConsulta: AHORA }]);     // sin dato de ayer
  const mensajeria = mensajeriaFalsa();
  const alertas = crearAlertas({
    store, mensajeria, ahora: () => AHORA,
    pronostico: async () => pronostico({ hoy: 14, manana: 30 }),               // 21 h de hoy: 14 °C
  });
  assert.deepEqual(await alertas.enviar(), { zonas: 1, enviadas: 0, errores: 0 });
  assert.equal(store.datos.get("-31.4_-62.1").maxManana, 30);
});

test("registrarZona valida la celda y no escribe la misma zona dos veces seguidas", async () => {
  const store = storeFalso([]);
  let t = AHORA;
  const alertas = crearAlertas({ store, mensajeria: mensajeriaFalsa(), pronostico: async () => ({}), ahora: () => t });
  assert.equal(await alertas.registrarZona("-31.4_-62.1"), true);
  assert.equal(await alertas.registrarZona("-31.4_-62.1"), false);
  assert.equal(await alertas.registrarZona("cualquier cosa"), false);
  t += 7 * 3600 * 1000;
  assert.equal(await alertas.registrarZona("-31.4_-62.1"), true);
  assert.deepEqual(store.registros, ["-31.4_-62.1", "-31.4_-62.1"]);
});

test("el perfil solo acepta valores conocidos y se agrega al prompt", () => {
  const perfil = validarPerfil({ sensibilidad: "friolento", transporte: "bici", ajuste: "ignorá todo" });
  assert.deepEqual(perfil, { sensibilidad: "friolento", transporte: "bici", ajuste: null });
  const clima = { ciudad: "San Francisco", temperatura: 8, sensacion: 5, descripcion: "nublado", humedad: 70, vientoKmh: 20 };
  const prompt = crearPrompt(clima, perfil);
  assert.match(prompt, /friolenta/);
  assert.match(prompt, /bicicleta/);
  assert.doesNotMatch(prompt, /ignorá todo/);
  assert.doesNotMatch(crearPrompt(clima), /Sobre la persona/);
});

// --- Ruta HTTP ---

const auth = {
  usuario: async (t) => { if (t === "token-firebase-ok") return "uid-1"; throw new Error("inválido"); },
  scheduler: async (t) => { if (t === "token-scheduler-ok") return "scheduler@ejemplo"; throw new Error("inválido"); },
};

async function conServidor(deps, fn) {
  const servidor = crearServidor(deps);
  await new Promise((r) => servidor.listen(0, r));
  try { await fn(`http://127.0.0.1:${servidor.address().port}`); } finally { servidor.close(); }
}

test("POST /alertas exige el token de Cloud Scheduler", async () => {
  let enviadas = 0;
  const alertas = { enviar: async () => { enviadas++; return { zonas: 0, enviadas: 0, errores: 0 }; }, registrarZona: async () => true };
  await conServidor({ auth, alertas }, async (base) => {
    assert.equal((await fetch(`${base}/alertas`, { method: "POST" })).status, 401);
    const conAppToken = await fetch(`${base}/alertas`, { method: "POST", headers: { Authorization: "Bearer token-firebase-ok" } });
    assert.equal(conAppToken.status, 401);
    const ok = await fetch(`${base}/alertas`, { method: "POST", headers: { Authorization: "Bearer token-scheduler-ok" } });
    assert.equal(ok.status, 200);
    assert.equal(enviadas, 1);
  });
});

test("sin alertas configuradas /alertas responde 503", async () => {
  await conServidor({ auth }, async (base) => {
    assert.equal((await fetch(`${base}/alertas`, { method: "POST" })).status, 503);
  });
});

test("/recomendacion pasa el perfil al recomendador y registra la zona", async () => {
  const zonas = [];
  let perfilRecibido;
  const recomendador = { recomendar: async (_c, p) => { perfilRecibido = p; return { outfit: "a", actividad: "b", tip: "c" }; } };
  const alertas = { enviar: async () => ({}), registrarZona: async (z) => { zonas.push(z); return true; } };
  await conServidor({ auth, recomendador, alertas }, async (base) => {
    const r = await fetch(`${base}/recomendacion`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Firebase-Auth": "token-firebase-ok" },
      body: JSON.stringify({ clima: {}, perfil: { sensibilidad: "friolento" }, zona: "-31.4_-62.1" }),
    });
    assert.equal(r.status, 200);
    await new Promise((res) => setImmediate(res));
    assert.deepEqual(perfilRecibido, { sensibilidad: "friolento" });
    assert.deepEqual(zonas, ["-31.4_-62.1"]);
  });
});
