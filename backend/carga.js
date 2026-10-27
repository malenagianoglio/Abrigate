// Prueba de carga para demostrar autoescalado y balanceo de carga en Cloud Run.
//
// Uso:  node carga.js <URL_DEL_SERVICIO> [pedidos_totales] [concurrencia]
// Ej.:  node carga.js https://abrigate-api-xxxx.a.run.app 3000 100
//
// Siempre pide la misma zona (San Francisco, Córdoba): después del primer pedido
// todo sale de la caché, así que la prueba NO consume cuota de OpenWeather.

const [, , base, totalArg = "3000", concArg = "100"] = process.argv;
if (!base) {
  console.error("Uso: node carga.js <URL_DEL_SERVICIO> [pedidos_totales] [concurrencia]");
  process.exit(1);
}

const TOTAL = Number(totalArg);
const CONCURRENCIA = Number(concArg);
const url = `${base.replace(/\/$/, "")}/clima?lat=-31.4258&lon=-62.0836`;

// El servicio en Cloud Run es privado: hay que mandar un token de identidad.
// Se obtiene con:  gcloud auth print-identity-token
const headers = process.env.TOKEN ? { Authorization: `Bearer ${process.env.TOKEN}` } : {};

const instancias = new Map();
const latencias = [];
let ok = 0, fallos = 0, hits = 0, siguiente = 0;

async function trabajador() {
  while (siguiente < TOTAL) {
    siguiente++;
    const inicio = performance.now();
    try {
      const resp = await fetch(url, { headers });
      await resp.arrayBuffer();
      latencias.push(performance.now() - inicio);
      if (resp.ok) ok++; else fallos++;
      if (resp.headers.get("x-cache") === "HIT") hits++;
      const inst = resp.headers.get("x-instancia") ?? "?";
      instancias.set(inst, (instancias.get(inst) ?? 0) + 1);
    } catch {
      fallos++;
    }
  }
}

(async () => {
  console.log(`Enviando ${TOTAL} pedidos con ${CONCURRENCIA} en paralelo a ${url}\n`);
  const t0 = performance.now();
  await Promise.all(Array.from({ length: CONCURRENCIA }, trabajador));
  const segundos = (performance.now() - t0) / 1000;

  latencias.sort((a, b) => a - b);
  const p = (q) => latencias[Math.floor(latencias.length * q)]?.toFixed(0) ?? "-";

  console.log(`Duración:         ${segundos.toFixed(1)} s (${(TOTAL / segundos).toFixed(0)} pedidos/s)`);
  console.log(`Respuestas OK:    ${ok}   Fallos: ${fallos}`);
  console.log(`Desde caché:      ${hits} (${((hits / TOTAL) * 100).toFixed(1)}%)`);
  console.log(`Latencia (ms):    p50=${p(0.5)}  p95=${p(0.95)}  p99=${p(0.99)}`);
  console.log(`\nInstancias que respondieron: ${instancias.size}`);
  for (const [inst, n] of [...instancias].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${inst.padEnd(40)} ${n} pedidos`);
  }
})();
