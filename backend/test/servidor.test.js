// Pruebas HTTP de las rutas de Abrigate Plus en server.js, con dependencias falsas.

const test = require("node:test");
const assert = require("node:assert/strict");
const { crearServidor } = require("../server");
const { ErrorCliente } = require("../suscripciones");

const auth = {
  usuario: async (t) => { if (t === "token-firebase-ok") return "uid-1"; throw new Error("inválido"); },
  pubsub: async (t) => { if (t === "token-pubsub-ok") return "pubsub@ejemplo"; throw new Error("inválido"); },
};

const llamadas = [];
const suscripciones = {
  verificar: async (token, uid) => {
    llamadas.push(["verificar", token, uid]);
    if (token === "ajeno") throw new ErrorCliente(403, "La compra pertenece a otro usuario");
    if (token === "rompe") throw new Error("Google no responde");
    return { plus: true, plan: "mensual", vence: "2026-12-12T12:00:00Z" };
  },
  estado: async (uid) => ({ plus: uid === "uid-1", plan: null, vence: null }),
  procesarRtdn: async () => ({ tipo: "suscripcion", activa: false }),
};

async function conServidor(deps, fn) {
  const servidor = crearServidor(deps);
  await new Promise((r) => servidor.listen(0, r));
  const base = `http://127.0.0.1:${servidor.address().port}`;
  try { await fn(base); } finally { servidor.close(); }
}

const json = (token, cuerpo) => ({
  method: "POST",
  headers: { "Content-Type": "application/json", ...(token && { Authorization: `Bearer ${token}` }) },
  body: JSON.stringify(cuerpo),
});

test("verificar con token válido devuelve el estado y pasa el uid del token", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    const r = await fetch(`${base}/suscripcion/verificar`, json("token-firebase-ok", { purchaseToken: "compra-1" }));
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), { plus: true, plan: "mensual", vence: "2026-12-12T12:00:00Z" });
    assert.deepEqual(llamadas.at(-1), ["verificar", "compra-1", "uid-1"]);
  });
});

test("el token también se acepta en X-Firebase-Auth (así lo manda la app a Cloud Run)", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    const r = await fetch(`${base}/suscripcion/estado`, { headers: { "X-Firebase-Auth": "token-firebase-ok" } });
    assert.equal(r.status, 200);
    const malo = await fetch(`${base}/suscripcion/estado`, { headers: { "X-Firebase-Auth": "falso" } });
    assert.equal(malo.status, 401);
  });
});

test("sin token o con token inválido responde 401", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    assert.equal((await fetch(`${base}/suscripcion/verificar`, json(null, { purchaseToken: "x" }))).status, 401);
    assert.equal((await fetch(`${base}/suscripcion/verificar`, json("falso", { purchaseToken: "x" }))).status, 401);
    assert.equal((await fetch(`${base}/suscripcion/estado`)).status, 401);
  });
});

test("los errores de cliente conservan su código y los de Google dan 500", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    assert.equal((await fetch(`${base}/suscripcion/verificar`, json("token-firebase-ok", { purchaseToken: "ajeno" }))).status, 403);
    assert.equal((await fetch(`${base}/suscripcion/verificar`, json("token-firebase-ok", { purchaseToken: "rompe" }))).status, 500);
  });
});

test("un JSON mal formado responde 400", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    const r = await fetch(`${base}/suscripcion/verificar`, {
      method: "POST", headers: { Authorization: "Bearer token-firebase-ok" }, body: "{no es json",
    });
    assert.equal(r.status, 400);
  });
});

test("estado devuelve el plan del usuario autenticado", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    const r = await fetch(`${base}/suscripcion/estado`, { headers: { Authorization: "Bearer token-firebase-ok" } });
    assert.equal(r.status, 200);
    assert.equal((await r.json()).plus, true);
  });
});

test("/rtdn exige el token de Pub/Sub", async () => {
  await conServidor({ suscripciones, auth }, async (base) => {
    const cuerpo = { message: { data: "e30=" } };
    assert.equal((await fetch(`${base}/rtdn`, json("token-firebase-ok", cuerpo))).status, 401);
    const ok = await fetch(`${base}/rtdn`, json("token-pubsub-ok", cuerpo));
    assert.equal(ok.status, 200);
  });
});

test("sin dependencias configuradas las rutas Plus responden 503 y /salud sigue andando", async () => {
  await conServidor({}, async (base) => {
    assert.equal((await fetch(`${base}/suscripcion/estado`)).status, 503);
    assert.equal((await fetch(`${base}/salud`)).status, 200);
  });
});
