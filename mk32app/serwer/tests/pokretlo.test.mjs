// Przejmowanie pokrętła — czy strona wie, że NAPRAWDĘ je dostała.
//
// ⛔ Most odmawia bez słowa: prośbę o ognisko zajęte przez pulpit po prostu
// odrzuca, a jedyny ślad zostaje w dzienniku panelu (GSB 2026-09-22:
// „klient-2 prosi o pokrętło zajęte przez pulpit — odmawiam"). Serwer meldował
// wtedy stronie przejęcie, którego nie było, a obroty szły do pulpitu, który
// pod spodem przesuwał kafelki i uruchamiał programy. Te testy pilnują, żeby
// milczenie mostu nie było znowu czytane jako zgoda.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const logi = mkdtempSync(join(tmpdir(), "panorama-pokretlo-test-"));
process.env.LOGI_DIR = logi;
process.env.POZIOM = "blad";
// Skracamy oba odstępy — inaczej jeden test czekałby pięć sekund na to samo.
process.env.POKRETLO_POTWIERDZENIE_MS = "20";
process.env.POKRETLO_ODSTEP_MS = "20";
const { Pokretlo } = await import("../server/pokretlo.mjs");
process.on("exit", () => rmSync(logi, { recursive: true, force: true }));

/** Pokrętło z podstawionym gniazdem: zapisujemy, co poszło do mostu. */
function pokretlo(t) {
  const p = new Pokretlo("/nie/ma/takiego.sock");
  p.wyslane = [];
  p.zdarzenia = [];
  p.polaczone = true;
  p.gniazdo = { write: (tekst) => p.wyslane.push(JSON.parse(tekst)) };
  p.on("zdarzenie", (z) => p.zdarzenia.push(z));
  t.after(() => p.przerwijStaranie());
  return p;
}

const prosby = (p) => p.wyslane.filter((w) => w.cmd === "ognisko" && w.gdzie === "pulpit").length;
const odmowy = (p) => p.zdarzenia.filter((z) => z.typ === "ognisko" && z.odmowa).length;
const odczekaj = (ms) => new Promise((r) => setTimeout(r, ms));

test("potwierdzone przejęcie nie ponawia prośby", async (t) => {
  const p = pokretlo(t);
  p.wezOgnisko();
  p.przyjmij(JSON.stringify({ typ: "ognisko", gdzie: "pulpit" }));
  await odczekaj(120);
  assert.equal(prosby(p), 1);
  assert.equal(odmowy(p), 0);
  assert.equal(p.mamyOgnisko, true);
  assert.equal(p.stan().mamy, true);
  assert.deepEqual(
    p.zdarzenia.at(-1),
    { typ: "ognisko", gdzie: "pulpit", mamy: true },
  );
});

test("milczenie mostu kończy się ponowieniami i meldunkiem odmowy", async (t) => {
  const p = pokretlo(t);
  p.wezOgnisko();
  await odczekaj(250);
  // Pulpit ustępuje pokrętło JEDNORAZOWO, w chwili uruchamiania aplikacji, więc
  // prośba wysłana ułamek sekundy za wcześnie przepada — stąd ponowienia.
  assert.equal(prosby(p), 3);
  assert.equal(odmowy(p), 1);
  assert.equal(p.mamyOgnisko, false);
  assert.equal(p.stan().mamy, false);
});

test("oddanie pokrętła przerywa staranie — żadnych ponowień po fakcie", async (t) => {
  const p = pokretlo(t);
  p.wezOgnisko();
  p.oddajOgnisko();
  await odczekaj(120);
  assert.equal(prosby(p), 1);
  assert.equal(odmowy(p), 0);
});

test("odebrane pokrętło melduje się stronie, ale nie jako odmowa", async (t) => {
  const p = pokretlo(t);
  p.wezOgnisko();
  p.przyjmij(JSON.stringify({ typ: "ognisko", gdzie: "pulpit" }));
  p.przyjmij(JSON.stringify({ typ: "ognisko", gdzie: "panel" }));
  await odczekaj(120);
  assert.equal(p.mamyOgnisko, false);
  assert.equal(odmowy(p), 0);
  assert.deepEqual(
    p.zdarzenia.at(-1),
    { typ: "ognisko", gdzie: "panel", mamy: false },
  );
});

// ⛔ Pytamy MOST, a nie własną pamięć. Wcześniej `wezOgnisko` wychodziło na skróty,
// gdy `ognisko` mówiło „pulpit" — i właśnie na tym wykładało się pokrętło na GSB
// 2026-09-22: przy zerwaniu i wznowieniu strumienia w ciągu milisekund nasze
// „oddaję" było już w drodze do mostu, a my jeszcze myśleliśmy, że mamy pokrętło,
// więc nie prosiliśmy o nie ponownie. Ognisko zostawało przy panelu na zawsze.
test("ponowna prośba przy pokrętle już u nas i tak idzie do mostu", async (t) => {
  const p = pokretlo(t);
  p.przyjmij(JSON.stringify({ typ: "ognisko", gdzie: "pulpit" }));
  assert.equal(p.wezOgnisko(), true);
  await odczekaj(120);
  assert.equal(prosby(p), 1);
  // Most odpowiada zgodą bez zmiany właściciela, więc ani ponowień, ani odmowy.
  assert.equal(odmowy(p), 0);
  assert.equal(p.mamyOgnisko, true);
});
