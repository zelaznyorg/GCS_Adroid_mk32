// Generuje mediamtx/mediamtx.yml na podstawie zrodla.json.
// Uruchamiane przez start.sh / start.ps1 przed startem MediaMTX.
import {
  readZrodla,
  readTelemetria,
  readArchiwum,
  validateZrodlo,
  generateMediamtxYml,
  katalogArchiwum,
} from "./zrodla-lib.mjs";

const zrodla = readZrodla();

if (zrodla.length === 0) {
  console.warn("Uwaga: zrodla.json nie zawiera żadnego źródła obrazu.");
}

const widziane = new Set();
for (const z of zrodla) {
  const err = validateZrodlo(z);
  if (err) {
    console.error("BŁĄD: " + err);
    process.exit(1);
  }
  if (widziane.has(z.id)) {
    console.error(`BŁĄD: zduplikowane 'id' źródła: "${z.id}".`);
    process.exit(1);
  }
  widziane.add(z.id);
}

const arch = readArchiwum();
const yml = generateMediamtxYml(zrodla, arch);
const tel = readTelemetria();

console.log(`Wygenerowano ${yml} (${zrodla.length} źródeł: ${zrodla.map((z) => z.id).join(", ") || "brak"}).`);
console.log(`Telemetria: ${tel.host}:${tel.port}`);

if (!arch.wlaczone) {
  console.log("Archiwum: wyłączone.");
} else {
  console.log(`Archiwum: ${katalogArchiwum(arch)} — telemetria zawsze, obraz: ${arch.wideo}, ` +
    `limit ${arch.limitGb} GB / ${arch.trzymajDni} dni.`);
  if (arch.wideo === "zawsze") {
    // Ostrzeżenie, nie zakaz: bywa świadomym wyborem przed lotem, który MUSI
    // mieć nagranie. Ale ma być widoczne w konsoli, a nie dopiero w rachunku
    // za pasmo i zajęty slot kamery.
    console.warn("UWAGA: tryb 'zawsze' trzyma strumień z kamery bez przerwy — " +
      "obciąża łącze radiowe i zajmuje slot ZR30 także wtedy, gdy nikt nie ogląda.");
  }
}
