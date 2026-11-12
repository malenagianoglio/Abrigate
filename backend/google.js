// Integraciones reales con Google para Abrigate Plus. En Cloud Run las
// credenciales salen de la cuenta de servicio del servicio (ADC): no hay claves
// en el código ni en Secret Manager.

const { createHash } = require("node:crypto");
const { GoogleAuth, OAuth2Client } = require("google-auth-library");
const { initializeApp, getApps } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");

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

// Quién hace cada pedido.
function crearAutenticacion({ audienciaRtdn, cuentaRtdn }) {
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
  };
}

module.exports = { crearPlay, crearStore, crearAutenticacion };
