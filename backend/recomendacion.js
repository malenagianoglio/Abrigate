// Recomendación de vestimenta con Gemini, generada en el servidor.
//
// Por qué está acá y no en la app: el proyecto de Firebase (abrigate-fed6f) tiene
// facturación vinculada, y en ese caso Google cobra la API de Gemini con créditos
// prepagos. Una clave de un proyecto de Google Cloud SIN facturación usa el nivel
// gratuito (igual que en proyecto final, tarea PF-31). Esa clave no puede ir dentro
// del APK porque cualquiera la extraería, así que la usa solo este servicio.

const { ErrorCliente } = require("./suscripciones");

const URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/models";

// TP6: personalización. Solo se aceptan valores de una lista cerrada, así el
// perfil no puede usarse para meter texto libre en el prompt.
const SENSIBILIDAD = {
  friolento: "La persona es friolenta: recomendá un poco más de abrigo de lo habitual.",
  caluroso: "La persona es calurosa: recomendá un poco menos de abrigo de lo habitual.",
};
const TRANSPORTE = {
  "a pie": "Se mueve a pie.",
  bici: "Se mueve en bicicleta: tené en cuenta el viento y la lluvia.",
  auto: "Se mueve en auto, así que pasa poco tiempo a la intemperie.",
  colectivo: "Se mueve en colectivo: incluí la espera en la parada.",
};
const AJUSTE = {
  mas_abrigo: "Según sus valoraciones, suele tener más frío que lo esperado.",
  menos_abrigo: "Según sus valoraciones, suele tener más calor que lo esperado.",
};

function validarPerfil(p) {
  const de = (tabla, v) => (typeof v === "string" && Object.hasOwn(tabla, v) ? v : null);
  return {
    sensibilidad: de(SENSIBILIDAD, p?.sensibilidad),
    transporte: de(TRANSPORTE, p?.transporte),
    ajuste: de(AJUSTE, p?.ajuste),
  };
}

function textoPerfil(perfil) {
  const lineas = [
    SENSIBILIDAD[perfil.sensibilidad],
    TRANSPORTE[perfil.transporte],
    AJUSTE[perfil.ajuste],
  ].filter(Boolean);
  return lineas.length ? `\nSobre la persona: ${lineas.join(" ")}\n` : "";
}

function crearPrompt(c, perfil = validarPerfil(null)) {
  return `Sos un asistente de vestimenta argentino. Hablás en español rioplatense,
breve y directo, sin emojis.

Clima actual en ${c.ciudad}: ${c.temperatura}°C,
sensación térmica ${c.sensacion}°C, ${c.descripcion},
humedad ${c.humedad}%, viento ${c.vientoKmh} km/h.
${textoPerfil(perfil)}
Respondé únicamente con un JSON con esta forma exacta:
{"outfit": "...", "actividad": "...", "tip": "..."}

outfit: qué ponerse hoy, máximo 20 palabras.
actividad: una actividad recomendada para este clima, máximo 15 palabras.
tip: un consejo práctico y concreto, máximo 15 palabras.`;
}

// Valida y normaliza el clima que manda la app. Los textos se recortan para que
// nadie use el campo "ciudad" para meter instrucciones largas en el prompt.
function validarClima(c) {
  const texto = (v, max) => (typeof v === "string" ? v.replace(/[\r\n]+/g, " ").trim().slice(0, max) : null);
  const numero = (v, min, max) => (Number.isFinite(v) && v >= min && v <= max ? Math.round(v) : null);
  const clima = {
    ciudad: texto(c?.ciudad, 60),
    temperatura: numero(c?.temperatura, -60, 60),
    sensacion: numero(c?.sensacion, -80, 80),
    descripcion: texto(c?.descripcion, 60),
    humedad: numero(c?.humedad, 0, 100),
    vientoKmh: numero(c?.vientoKmh, 0, 400),
  };
  const faltan = Object.entries(clima).filter(([, v]) => v === null || v === "").map(([k]) => k);
  if (faltan.length) throw new ErrorCliente(400, `Clima inválido: ${faltan.join(", ")}`);
  return clima;
}

// Cuando Gemini está saturado (500/503) o no contesta a tiempo:
// 1) se reintenta el modelo principal tras una espera corta, porque en proyecto
//    final se vio que gemini-3.1-flash-lite se recupera a los pocos segundos;
// 2) si sigue sin responder, se usa un modelo de respaldo. En las pruebas del
//    12/11/2026, 3.1-flash-lite no respondía en 30 s y 2.5-flash sí, en ~3 s.
// El total queda por debajo de los 30 s que espera la app.
const MODELO_PRINCIPAL = "gemini-3.1-flash-lite";
const MODELO_RESPALDO = "gemini-2.5-flash";
const ESPERA_REINTENTO_MS = 1500;
const TIEMPO_MAXIMO_MS = 7000;
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

function crearRecomendador({
  apiKey,
  modelo = MODELO_PRINCIPAL,
  modeloRespaldo = MODELO_RESPALDO,
  fetchFn = fetch,
  espera = ESPERA_REINTENTO_MS,
  esperar = dormir,
}) {
  const intentos = [
    { modelo, espera: 0 },
    { modelo, espera },
    ...(modeloRespaldo && modeloRespaldo !== modelo ? [{ modelo: modeloRespaldo, espera: 0 }] : []),
  ];

  async function pedir(prompt, nombreModelo) {
    const generationConfig = { temperature: 0.8, responseMimeType: "application/json" };
    // Los modelos 2.5 "piensan" antes de responder: para una respuesta corta
    // como esta no hace falta, y apagarlo la hace más rápida y gasta menos cuota.
    if (nombreModelo.startsWith("gemini-2.5")) generationConfig.thinkingConfig = { thinkingBudget: 0 };
    try {
      return await fetchFn(`${URL_GEMINI}/${nombreModelo}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
        body: JSON.stringify({
          contents: [{ role: "user", parts: [{ text: prompt }] }],
          generationConfig,
        }),
        signal: AbortSignal.timeout(TIEMPO_MAXIMO_MS),
      });
    } catch (e) {
      if (e.name === "TimeoutError" || e.name === "AbortError") return { status: 504, sinRespuesta: true };
      throw e;
    }
  }

  const saturado = (r) => r.status === 500 || r.status === 503 || r.status === 504;

  async function recomendar(datosClima, datosPerfil) {
    const clima = validarClima(datosClima);
    const prompt = crearPrompt(clima, validarPerfil(datosPerfil));

    let resp;
    for (const [i, intento] of intentos.entries()) {
      if (i > 0) {
        console.warn(JSON.stringify({
          severity: "WARNING",
          message: `Gemini respondió ${resp.status}; reintento con ${intento.modelo}`,
        }));
      }
      if (intento.espera) await esperar(intento.espera);
      resp = await pedir(prompt, intento.modelo);
      if (!saturado(resp)) break;
    }

    if (saturado(resp)) {
      throw new ErrorCliente(503, "Gemini está saturado en este momento. Probá de nuevo en un rato.");
    }
    if (resp.status === 429) {
      throw new ErrorCliente(503, "Se alcanzó el límite gratuito de Gemini. Probá en un rato.");
    }
    if (!resp.ok) {
      const detalle = (await resp.text()).slice(0, 300);
      throw new Error(`Gemini respondió ${resp.status}: ${detalle}`);
    }

    const datos = await resp.json();
    const texto = datos.candidates?.[0]?.content?.parts?.map((p) => p.text || "").join("") || "";
    let reco;
    try {
      reco = JSON.parse(texto);
    } catch {
      throw new Error("Gemini no devolvió un JSON válido");
    }
    const campo = (v) => (typeof v === "string" && v.trim() ? v.trim() : null);
    const resultado = { outfit: campo(reco.outfit), actividad: campo(reco.actividad), tip: campo(reco.tip) };
    if (!resultado.outfit || !resultado.actividad || !resultado.tip) {
      throw new Error("A la respuesta de Gemini le faltan campos");
    }
    return resultado;
  }

  return { recomendar };
}

module.exports = { crearRecomendador, validarClima, validarPerfil, crearPrompt };
