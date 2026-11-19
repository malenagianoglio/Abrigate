// TP6: alertas de cambio brusco de clima por zona, enviadas con Firebase Cloud Messaging.
//
// Cómo funciona:
// - La app manda su zona (una celda de 0,1°, unos 11 km, sin datos del usuario) junto
//   con cada pedido de recomendación, y se suscribe al tema FCM "zona_<celda>".
// - Cloud Scheduler llama a POST /alertas todos los días a las 20 h. Para cada zona
//   consultada en los últimos 14 días se compara la máxima prevista para mañana con
//   la máxima prevista para hoy (que se guardó la noche anterior). Si cambia 8 °C o
//   más, o se espera lluvia fuerte, se manda UNA push al tema de esa zona.
// - El backend nunca guarda tokens de dispositivos ni qué usuario está en qué zona:
//   eso lo resuelve FCM con los temas.

const DIAS_ACTIVA = 14;
const SALTO_MINIMO = 8;              // °C entre la máxima de hoy y la de mañana
const LLUVIA_FUERTE_MM = 10;         // mm en un bloque de 3 h
const REGISTRO_CADA_MS = 6 * 60 * 60 * 1000;   // no escribir la misma zona más de 1 vez cada 6 h
const DIA_MS = 24 * 60 * 60 * 1000;

// "-31.4_-62.1": latitud y longitud con un decimal. Es más grueso que la celda del
// caché de /clima (≈ 1 km) para no revelar el barrio del usuario.
const FORMATO_ZONA = /^-?\d{1,2}\.\d_-?\d{1,3}\.\d$/;

function validarZona(zona) {
  if (typeof zona !== "string" || !FORMATO_ZONA.test(zona)) return null;
  const [lat, lon] = zona.split("_").map(Number);
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return zona;
}

function centroZona(zona) {
  const [lat, lon] = zona.split("_").map(Number);
  return { lat, lon };
}

const temaDeZona = (zona) => `zona_${zona}`;

// Fecha local "AAAA-MM-DD" a partir de un instante y el desfasaje de la zona (segundos).
function fechaLocal(ms, desfasajeSeg) {
  return new Date(ms + desfasajeSeg * 1000).toISOString().slice(0, 10);
}

// Resume la respuesta de OpenWeather /data/2.5/forecast (bloques de 3 h) en la
// máxima de hoy y la de mañana, en la hora local de la zona.
function resumirPronostico(datos, ahora) {
  const desfasaje = Number(datos?.city?.timezone) || 0;
  const hoy = fechaLocal(ahora, desfasaje);
  const manana = fechaLocal(ahora + DIA_MS, desfasaje);
  const dias = { [hoy]: { max: null, lluviaFuerte: false }, [manana]: { max: null, lluviaFuerte: false } };

  for (const b of datos?.list || []) {
    const dia = dias[fechaLocal(b.dt * 1000, desfasaje)];
    if (!dia) continue;
    const max = b.main?.temp_max ?? b.main?.temp;
    if (Number.isFinite(max)) dia.max = dia.max === null ? max : Math.max(dia.max, max);
    const id = b.weather?.[0]?.id ?? 0;
    const mm = b.rain?.["3h"] ?? 0;
    if (mm >= LLUVIA_FUERTE_MM || (id >= 200 && id < 300) || (id >= 502 && id <= 504)) dia.lluviaFuerte = true;
  }
  return { hoy: { fecha: hoy, ...dias[hoy] }, manana: { fecha: manana, ...dias[manana] } };
}

// Decide si hay que avisar. maxHoy es la máxima que se había previsto para hoy.
function evaluar({ maxHoy, manana }) {
  if (manana.max === null) return null;
  const salto = maxHoy === null || maxHoy === undefined ? 0 : Math.round(manana.max - maxHoy);
  if (Math.abs(salto) >= SALTO_MINIMO) {
    return {
      titulo: salto < 0 ? `Mañana bajan ${-salto} °C` : `Mañana suben ${salto} °C`,
      texto: "Tocá para ver qué ponerte.",
      salto,
    };
  }
  if (manana.lluviaFuerte) {
    return { titulo: "Mañana se espera lluvia fuerte", texto: "Tocá para ver qué llevar.", salto };
  }
  return null;
}

function crearAlertas({ store, pronostico, mensajeria, ahora = Date.now }) {
  // Zonas que esta instancia ya registró hace poco: evita una escritura en
  // Firestore por cada recomendación.
  const registradas = new Map();

  async function registrarZona(zonaCruda) {
    const zona = validarZona(zonaCruda);
    if (!zona) return false;
    const t = ahora();
    if (t - (registradas.get(zona) || 0) < REGISTRO_CADA_MS) return false;
    registradas.set(zona, t);
    await store.registrar(zona, t);
    return true;
  }

  async function enviar() {
    const t = ahora();
    const zonas = await store.activas(t - DIAS_ACTIVA * DIA_MS);
    const resultado = { zonas: zonas.length, enviadas: 0, errores: 0 };

    for (const z of zonas) {
      try {
        const resumen = resumirPronostico(await pronostico(centroZona(z.id)), t);
        if (z.alertaEnviada === resumen.hoy.fecha) continue;           // máximo 1 por día
        // Se compara con la máxima que se había previsto para hoy (guardada anoche). A las
        // 20 h el pronóstico ya casi no tiene horas de hoy, así que la primera noche de
        // una zona solo se guarda el dato y no se compara la temperatura.
        const maxHoy = z.fechaMaxManana === resumen.hoy.fecha ? z.maxManana : null;
        const alerta = evaluar({ maxHoy, manana: resumen.manana });
        const cambios = { maxManana: resumen.manana.max, fechaMaxManana: resumen.manana.fecha };

        if (alerta) {
          await mensajeria.send({
            topic: temaDeZona(z.id),
            notification: { title: alerta.titulo, body: alerta.texto },
            android: {
              ttl: 6 * 60 * 60 * 1000,                                 // pasadas 6 h ya no sirve
              notification: { channelId: "alertas_clima", tag: "alerta_clima" },
            },
            data: { destino: "recomendacion", canal: "alertas_clima" },
          });
          cambios.alertaEnviada = resumen.hoy.fecha;
          resultado.enviadas++;
          console.log(JSON.stringify({ severity: "INFO", message: "Alerta enviada", zona: z.id, salto: alerta.salto }));
        }
        await store.actualizar(z.id, cambios);
      } catch (e) {
        resultado.errores++;
        console.error(JSON.stringify({ severity: "ERROR", message: `Alerta de ${z.id}: ${e.message}` }));
      }
    }
    return resultado;
  }

  return { registrarZona, enviar };
}

module.exports = { crearAlertas, validarZona, resumirPronostico, evaluar, temaDeZona };
