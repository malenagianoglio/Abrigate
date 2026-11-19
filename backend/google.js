// Integraciones reales con Google para Abrigate Plus. En Cloud Run las
// credenciales salen de la cuenta de servicio del servicio (ADC): no hay claves
// en el código ni en Secret Manager.

const { createHash } = require("node:crypto");
const { GoogleAuth, OAuth2Client } = require("google-auth-library");
const { initializeApp, getApps } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const { getMessaging } = require("firebase-admin/messaging");

function app() {
  return getApps()[0] || initializeApp();
}

// Google Play Developer API (androidpublisher v3), por REST.
function crearPlay(paquete) {
  const auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });
  const base =
    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
    `${encodeURIComponent(paquete)}/purchases`;

  return {
    async obtenerSuscripcion(token) {
      const cliente = await auth.getClient();
      const r = await cliente.request({
        url: `${base}/subscriptionsv2/tokens/${encodeURIComponent(token)}`,
      });
      return r.data;
    },
    async confirmar(token) {
      const cliente = await auth.getClient();
      await cliente.request({
        url: `${base}/subscriptions/abrigate_plus/tokens/${encodeURIComponent(token)}:acknowledge`,
        method: "POST",
        data: {},
      });
    },
  };
}

// Firestore. El id del documento es un hash del token: el token completo no se
// guarda porque alcanza con poder encontrarlo cuando llega una RTDN.
function crearStore() {
  const coleccion = () => getFirestore(app()).collection("suscripciones");
  const id = (token) => createHash("sha256").update(token).digest("hex");
  return {
    guardar: (token, registro) => coleccion().doc(id(token)).set(registro),
    async buscarPorUid(uid) {
      const snap = await coleccion().where("uid", "==", uid).get();
      return snap.docs.map((d) => d.data());
    },
  };
}

// TP6: zonas consultadas (colección "zonas", id = celda de 0,1°). Solo se guarda
// la celda y fechas: ningún dato del usuario.
function crearStoreZonas() {
  const coleccion = () => getFirestore(app()).collection("zonas");
  return {
    registrar: (zona, t) => coleccion().doc(zona).set({ ultimaConsulta: t }, { merge: true }),
    async activas(desde) {
      const snap = await coleccion().where("ultimaConsulta", ">=", desde).get();
      return snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    },
    actualizar: (zona, datos) => coleccion().doc(zona).set(datos, { merge: true }),
  };
}

// TP6: envío de notificaciones con la API HTTP v1 de FCM. Usa la cuenta de
// servicio de Cloud Run (rol "Firebase Cloud Messaging Admin"), sin claves.
function crearMensajeria() {
  return { send: (mensaje) => getMessaging(app()).send(mensaje) };
}

// Quién hace cada pedido.
function crearAutenticacion({ audienciaRtdn, cuentaRtdn, audienciaAlertas, cuentaAlertas }) {
  const oauth = new OAuth2Client();
  return {
    // Token de Firebase Auth que manda la app (usuario anónimo). Devuelve el uid.
    async usuario(idToken) {
      const decodificado = await getAuth(app()).verifyIdToken(idToken);
      return decodificado.uid;
    },
    // Token OIDC que agrega Pub/Sub a cada push: así nadie más puede llamar a /rtdn.
    async pubsub(idToken) {
      const ticket = await oauth.verifyIdToken({ idToken, audience: audienciaRtdn });
      const datos = ticket.getPayload();
      if (cuentaRtdn && datos.email !== cuentaRtdn) throw new Error("Cuenta de Pub/Sub no autorizada");
      return datos.email;
    },
    // TP6: token OIDC de Cloud Scheduler para POST /alertas. Se exige la cuenta
    // y la audiencia configuradas: sin ellas nadie puede disparar envíos.
    async scheduler(idToken) {
      if (!cuentaAlertas || !audienciaAlertas) throw new Error("ALERTAS_CUENTA o ALERTAS_AUDIENCIA sin configurar");
      const ticket = await oauth.verifyIdToken({ idToken, audience: audienciaAlertas });
      const datos = ticket.getPayload();
      if (datos.email !== cuentaAlertas || !datos.email_verified) throw new Error("Cuenta no autorizada");
      return datos.email;
    },
  };
}

module.exports = { crearPlay, crearStore, crearStoreZonas, crearMensajeria, crearAutenticacion };
