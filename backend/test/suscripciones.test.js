// Pruebas de la lógica de Abrigate Plus con dobles en memoria (sin Google Cloud).
// Correr con: npm test

const test = require("node:test");
const assert = require("node:assert/strict");
const { crearSuscripciones, ErrorCliente } = require("../suscripciones");

const AHORA = Date.parse("2026-11-12T12:00:00Z");
const TOKEN = "token-de-compra-de-prueba-123";

// Respuesta con la forma de purchases.subscriptionsv2.get
function respuestaPlay(cambios = {}) {
  return {
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
    externalAccountIdentifiers: { obfuscatedExternalAccountId: "uid-1" },
    lineItems: [{
      productId: "abrigate_plus",
      expiryTime: "2026-12-12T12:00:00Z",
      offerDetails: { basePlanId: "mensual" },
    }],
    ...cambios,
  };
}

function armar(respuesta) {
  const confirmados = [];
  const guardados = new Map();
  const play = {
    obtenerSuscripcion: async () => respuesta,
    confirmar: async (t) => { confirmados.push(t); },
  };
  const store = {
    guardar: async (t, r) => { guardados.set(t, r); },
    buscarPorUid: async (uid) => [...guardados.values()].filter((r) => r.uid === uid),
  };
  const s = crearSuscripciones({ play, store, paquete: "com.utn.abrigate", ahora: () => AHORA });
  return { s, confirmados, guardados };
}

test("una compra activa da Plus y se confirma (acknowledge)", async () => {
  const { s, confirmados } = armar(respuestaPlay());
  const r = await s.verificar(TOKEN, "uid-1");
  assert.deepEqual(r, { plus: true, plan: "mensual", vence: "2026-12-12T12:00:00Z" });
  assert.deepEqual(confirmados, [TOKEN]);
});

test("una compra ya confirmada no se vuelve a confirmar", async () => {
  const { s, confirmados } = armar(respuestaPlay({ acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" }));
  await s.verificar(TOKEN, "uid-1");
  assert.equal(confirmados.length, 0);
});

test("el período de gracia mantiene Plus", async () => {
  const { s } = armar(respuestaPlay({ subscriptionState: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" }));
  assert.equal((await s.verificar(TOKEN, "uid-1")).plus, true);
});

test("una suscripción vencida o cancelada no da Plus ni se confirma", async () => {
  for (const estado of ["SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_ON_HOLD"]) {
    const { s, confirmados } = armar(respuestaPlay({ subscriptionState: estado }));
    assert.equal((await s.verificar(TOKEN, "uid-1")).plus, false);
    assert.equal(confirmados.length, 0);
  }
});

test("una fecha de vencimiento pasada no da Plus aunque Google diga ACTIVE", async () => {
  const r = respuestaPlay();
  r.lineItems[0].expiryTime = "2026-11-01T00:00:00Z";
  const { s } = armar(r);
  assert.equal((await s.verificar(TOKEN, "uid-1")).plus, false);
});

test("no se puede usar la compra de otro usuario", async () => {
  const { s } = armar(respuestaPlay());
  await assert.rejects(s.verificar(TOKEN, "uid-intruso"), (e) => e instanceof ErrorCliente && e.status === 403);
});

test("un token de otro producto se rechaza", async () => {
  const { s } = armar(respuestaPlay({ lineItems: [{ productId: "otra_cosa" }] }));
  await assert.rejects(s.verificar(TOKEN, "uid-1"), (e) => e.status === 400);
});

test("un token vacío o corto se rechaza sin llamar a Google", async () => {
  const { s } = armar(respuestaPlay());
  await assert.rejects(s.verificar("", "uid-1"), (e) => e.status === 400);
});

test("estado() devuelve Plus para el uid que compró y no para otro", async () => {
  const { s } = armar(respuestaPlay());
  await s.verificar(TOKEN, "uid-1");
  assert.equal((await s.estado("uid-1")).plus, true);
  assert.deepEqual(await s.estado("uid-2"), { plus: false, plan: null, vence: null });
});

function pubsub(datos) {
  return { message: { data: Buffer.from(JSON.stringify(datos)).toString("base64") } };
}

test("una RTDN de cancelación actualiza el estado guardado", async () => {
  const respuesta = respuestaPlay();
  const { s, guardados } = armar(respuesta);
  await s.verificar(TOKEN, "uid-1");

  respuesta.subscriptionState = "SUBSCRIPTION_STATE_EXPIRED";
  const r = await s.procesarRtdn(pubsub({
    packageName: "com.utn.abrigate",
    subscriptionNotification: { notificationType: 13, purchaseToken: TOKEN },
  }));
  assert.equal(r.activa, false);
  assert.equal(guardados.get(TOKEN).activa, false);
  assert.equal((await s.estado("uid-1")).plus, false);
});

test("la notificación de prueba de Play Console se acepta sin hacer nada", async () => {
  const { s, guardados } = armar(respuestaPlay());
  const r = await s.procesarRtdn(pubsub({ packageName: "com.utn.abrigate", testNotification: { version: "1.0" } }));
  assert.equal(r.tipo, "prueba");
  assert.equal(guardados.size, 0);
});

test("una RTDN de otro paquete se ignora", async () => {
  const { s } = armar(respuestaPlay());
  const r = await s.procesarRtdn(pubsub({ packageName: "com.otra.app", subscriptionNotification: { purchaseToken: TOKEN } }));
  assert.equal(r.tipo, "otro-paquete");
});
