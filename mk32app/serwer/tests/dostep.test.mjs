// Żetony widzów: co znika przy sprzątaniu, a co ma przeżyć.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const dane = mkdtempSync(join(tmpdir(), "panorama-dostep-test-"));
process.env.DATA_DIR = dane;
process.env.LOGI_DIR = dane;
process.env.POZIOM = "blad";

const DZIEN = 86400000;
const teraz = Date.now();
const zeton = (id, zmiany) => ({
  id,
  sekret: `sekret-${id}`,
  imie: id,
  rola: "widz",
  utworzono: teraz - 200 * DZIEN,
  ostatnioWidziany: teraz,
  odciety: false,
  ...zmiany,
});

writeFileSync(
  join(dane, "dostep.json"),
  JSON.stringify({
    zaproszenia: [],
    zetony: [
      zeton("zywy"),
      zeton("swiezo-odciety", { odciety: true, ostatnioWidziany: teraz - 2 * DZIEN }),
      zeton("dawno-odciety", { odciety: true, ostatnioWidziany: teraz - 10 * DZIEN }),
      zeton("zapomniany", { ostatnioWidziany: teraz - 100 * DZIEN }),
    ],
    ustawienia: {},
    dziennik: [],
  }),
);

const dostep = await import("../server/dostep.mjs");
process.on("exit", () => rmSync(dane, { recursive: true, force: true }));

test("sprzątanie zabiera tylko żetony, po których nic nie zostało", () => {
  assert.equal(dostep.sprzatajZetony(), 2);
  const zostaly = dostep.zetony().map((z) => z.id).sort();
  // Żywy zostaje zawsze; świeżo odcięty zostaje, bo admin może chcieć zobaczyć,
  // kogo odciął wczoraj.
  assert.deepEqual(zostaly, ["swiezo-odciety", "zywy"]);
  // Zapis trafił na dysk, a nie tylko do pamięci procesu.
  const zPliku = JSON.parse(readFileSync(join(dane, "dostep.json"), "utf8"));
  assert.equal(zPliku.zetony.length, 2);
});

test("odcięty żeton nie wpuszcza, żywy wpuszcza", () => {
  assert.equal(dostep.sprawdzZeton("zywy.sekret-zywy").imie, "zywy");
  assert.equal(dostep.sprawdzZeton("swiezo-odciety.sekret-swiezo-odciety"), null);
  assert.equal(dostep.sprawdzZeton("zywy.zly-sekret"), null);
  assert.equal(dostep.sprawdzZeton("zywy"), null);
  assert.equal(dostep.sprawdzZeton(null), null);
});

test("ostatnio widziany trafia na dysk, żeby przeżyć restart", () => {
  dostep.sprawdzZeton("zywy.sekret-zywy");
  const zPliku = JSON.parse(readFileSync(join(dane, "dostep.json"), "utf8"));
  const z = zPliku.zetony.find((x) => x.id === "zywy");
  assert.ok(z.ostatnioWidziany >= teraz, "znacznik nie został zapisany");
});
