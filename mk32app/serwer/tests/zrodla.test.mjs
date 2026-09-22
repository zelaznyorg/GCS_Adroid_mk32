// Konfiguracja ścieżek MediaMTX — to, co decyduje, czy stacja NAGRYWA i kogo
// wpuszcza na obraz.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const dane = mkdtempSync(join(tmpdir(), "panorama-zrodla-test-"));
process.env.DATA_DIR = dane;
process.env.LOGI_DIR = dane;
process.env.POZIOM = "blad";
writeFileSync(
  join(dane, "zrodla.json"),
  JSON.stringify({
    zrodla: [
      { id: "uav", nazwa: "ZR30", rtspGlowny: "rtsp://1.2.3.4/video1", rtspPomocniczy: "rtsp://1.2.3.4/video2" },
      { id: "ukryty", nazwa: "Ukryty", rtspGlowny: "rtsp://1.2.3.4/video3", widoczne: false },
      { id: "dji", nazwa: "Mavic", nadawany: true },
    ],
  }),
);
const { pathsForZrodlo, readZrodla, zrodloSciezki } = await import("../scripts/zrodla-lib.mjs");
process.on("exit", () => rmSync(dane, { recursive: true, force: true }));

const zrodlo = (id) => readZrodla().find((z) => z.id === id);
const ARCHIWUM_WYLACZONE = { wlaczone: false, wideo: "przy-widzach", trzymajDni: 30, katalog: "archiwum" };
const ARCHIWUM_BEZ_WIDEO = { wlaczone: true, wideo: "nie", trzymajDni: 30, katalog: "archiwum" };
const ARCHIWUM_NAGRYWA = { wlaczone: true, wideo: "przy-widzach", trzymajDni: 30, katalog: "archiwum" };

// ⛔ Ścieżki dosyłamy do MediaMTX metodą PATCH, czyli SCALENIEM. Klucz pominięty
// zostaje po staremu, więc wyłączenie nagrywania MUSI wysłać `record: false` —
// inaczej stacja pisze na kartę dalej, mimo że panel mówi „nie nagrywam".
test("wyłączone nagrywanie wysyła record: false, a nie brak klucza", () => {
  for (const ustawienia of [ARCHIWUM_WYLACZONE, ARCHIWUM_BEZ_WIDEO, null]) {
    for (const id of ["uav", "dji", "ukryty"]) {
      for (const { name, conf } of pathsForZrodlo(zrodlo(id), ustawienia)) {
        assert.equal(conf.record, false, `${id} → ${name}`);
      }
    }
  }
});

test("włączone nagrywanie obejmuje strumień główny, nie pomocniczy", () => {
  const sciezki = pathsForZrodlo(zrodlo("uav"), ARCHIWUM_NAGRYWA);
  const glowna = sciezki.find((s) => s.name === "uav");
  const pomocnicza = sciezki.find((s) => s.name === "uav_pom");
  assert.equal(glowna.conf.record, true);
  assert.match(glowna.conf.recordPath, /wideo/);
  assert.equal(glowna.conf.recordDeleteAfter, "720h");
  // Pomocniczy to droga odwrotu dla przeglądarek bez H.265, nie druga kopia lotu.
  assert.equal(pomocnicza.conf.record, false);
});

test("źródło nadawane nie dostaje ani source, ani sourceOnDemand", () => {
  const [{ conf }] = pathsForZrodlo(zrodlo("dji"), ARCHIWUM_NAGRYWA);
  assert.equal("source" in conf, false);
  assert.equal("sourceOnDemand" in conf, false);
  assert.equal(conf.record, true);
});

// ⛔ Żeton mówi, KIM jesteś — nie otwiera każdej ścieżki, jaka przyjdzie do głowy.
test("ścieżkę da się przypisać do źródła, a wymyśloną odrzucić", () => {
  const lista = readZrodla();
  assert.equal(zrodloSciezki(lista, "uav").zrodlo.id, "uav");
  assert.equal(zrodloSciezki(lista, "uav").pomocniczy, false);
  assert.equal(zrodloSciezki(lista, "uav_pom").pomocniczy, true);
  assert.equal(zrodloSciezki(lista, "ukryty").zrodlo.widoczne, false);
  // Źródło bez drugiego adresu nie ma ścieżki pomocniczej — nawet jeśli ktoś ją zgadnie.
  assert.equal(zrodloSciezki(lista, "ukryty_pom"), null);
  assert.equal(zrodloSciezki(lista, "nie-ma-takiego"), null);
  assert.equal(zrodloSciezki(lista, ""), null);
  assert.equal(zrodloSciezki(lista, undefined), null);
});
