// Lógica de Abrigate Plus (TP4). No habla directo con Google: recibe `play` (la
// Play Developer API) y `store` (Firestore) ya armados. Así se puede probar con
// dobles en memoria y la integración real queda aislada en google.js.

const PRODUCTO = "abrigate_plus";

// Estados de subscriptionsv2 que dan acceso a Plus. En el período de gracia el
// usuario conserva Plus mientras Google reintenta el cobro.
const ESTADOS_CON_ACCESO = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
]);

class ErrorCliente extends Error {
  constructor(status, mensaje) {
    super(mensaje);
    this.status = status;
  }
}

function crearSuscripciones({ play, store, paquete, ahora = () => Date.now() }) {
  // Consulta el token a Google, confirma la compra si hace falta y guarda el estado.
  // `uidEsperado` viene del token de Firebase cuando el pedido lo hace la app.
  async function sincronizar(purchaseToken, uidEsperado = null) {
    if (typeof purchaseToken !== "string" || purchaseToken.length < 10) {
      throw new ErrorCliente(400, "purchaseToken inválido");
    }

    const sub = await play.obtenerSuscripcion(purchaseToken);
    const item = (sub.lineItems || []).find((li) => li.productId === PRODUCTO);
    if (!item) throw new ErrorCliente(400, "El token no corresponde a Abrigate Plus");

    const uidCompra = sub.externalAccountIdentifiers?.obfuscatedExternalAccountId || null;
    if (uidEsperado && uidCompra && uidCompra !== uidEsperado) {
      throw new ErrorCliente(403, "La compra pertenece a otro usuario");
    }

    const vence = item.expiryTime || null;
    const activa =
      ESTADOS_CON_ACCESO.has(sub.subscriptionState) &&
      (!vence || Date.parse(vence) > ahora());

    // Si nadie la confirma en 3 días, Google reembolsa la compra.
    if (activa && sub.acknowledgementState !== "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED") {
      await play.confirmar(purchaseToken);
    }

    const registro = {
      uid: uidCompra || uidEsperado,
      plan: item.offerDetails?.basePlanId || null,
      vence,
      estado: sub.subscriptionState,
      activa,
      actualizado: new Date(ahora()).toISOString(),
    };
    await store.guardar(purchaseToken, registro);
    return registro;
  }

  async function verificar(purchaseToken, uid) {
    const r = await sincronizar(purchaseToken, uid);
    return { plus: r.activa, plan: r.plan, vence: r.vence };
  }

  async function estado(uid) {
    const registros = await store.buscarPorUid(uid);
    const vigente = registros
      .filter((r) => r.activa && (!r.vence || Date.parse(r.vence) > ahora()))
      .sort((a, b) => Date.parse(b.vence || 0) - Date.parse(a.vence || 0))[0];
    return vigente
      ? { plus: true, plan: vigente.plan, vence: vigente.vence }
      : { plus: false, plan: null, vence: null };
  }

  // Notificación en tiempo real (RTDN) que Google Play publica en Pub/Sub y
  // Pub/Sub entrega por push: { message: { data: <base64 de un JSON> } }.
  async function procesarRtdn(cuerpo) {
    let datos;
    try {
      datos = JSON.parse(Buffer.from(cuerpo.message.data, "base64").toString("utf8"));
    } catch {
      throw new ErrorCliente(400, "Mensaje de Pub/Sub mal formado");
    }
    if (datos.packageName && datos.packageName !== paquete) return { tipo: "otro-paquete" };
    if (datos.testNotification) return { tipo: "prueba" };

    const n = datos.subscriptionNotification;
    if (!n?.purchaseToken) return { tipo: "ignorada" };

    const r = await sincronizar(n.purchaseToken);
    return { tipo: "suscripcion", notificationType: n.notificationType, activa: r.activa };
  }

  return { verificar, estado, procesarRtdn };
}

module.exports = { crearSuscripciones, ErrorCliente, PRODUCTO };
