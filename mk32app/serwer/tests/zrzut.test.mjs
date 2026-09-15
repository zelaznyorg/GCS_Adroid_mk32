import { test } from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const logi = mkdtempSync(join(tmpdir(), "panorama-zrzut-test-"));
process.env.LOGI_DIR = logi;
process.env.POZIOM = "blad";
const { OdbiorZrzutu } = await import("../server/zrzut.mjs");
process.on("exit", () => rmSync(logi, { recursive: true, force: true }));

class Gniazdo extends EventEmitter {
  destroyed = false;
  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    // Tak jak net.Socket: close przychodzi po destroy, nie w jego stosie.
    queueMicrotask(() => this.emit("close"));
  }
  wyslij(dane) { this.emit("data", Buffer.from(dane)); }
}

function odbiornik(t) {
  const o = new OdbiorZrzutu(h => h === "test" ? { id: "dji", haslo: h } : null);
  o.uruchomFfmpeg = () => {};
  o.odebrane = [];
  o.doFfmpeg = dane => o.odebrane.push(Buffer.from(dane));
  t.after(() => o.stop());
  return o;
}
const naglowek = '{"haslo":"test","fps":30}\n';
const zamkniete = () => new Promise(resolve => setImmediate(resolve));

test("nagłówek podzielony i obraz w tej samej paczce", t => {
  const o = odbiornik(t), g = new Gniazdo();
  o.przyjmij(g);
  g.wyslij(naglowek.slice(0, 8));
  assert.equal(o.polaczenie, null);
  g.wyslij(Buffer.concat([Buffer.from(naglowek.slice(8)), Buffer.alloc(4096, 7)]));
  assert.equal(o.polaczenie, g);
  assert.deepEqual(o.odebrane, [Buffer.alloc(4096, 7)]);
});

test("zły JSON, null i niepoprawny typ hasła nie przewracają odbiornika", t => {
  const o = odbiornik(t);
  for (const dane of ['null\n', '[]\n', '3\n', '{\n', '{"haslo":{}}\n', '{"haslo":"zle"}\n']) {
    const g = new Gniazdo();
    o.przyjmij(g);
    assert.doesNotThrow(() => g.wyslij(dane));
    assert.equal(g.destroyed, true);
    assert.equal(o.polaczenie, null);
  }
});

test("limit nagłówka obowiązuje także gdy kończy się nową linią", t => {
  const o = odbiornik(t);
  for (const koniec of ["", "\n"]) {
    const g = new Gniazdo();
    o.przyjmij(g);
    g.wyslij('{"haslo":"test","padding":"' + 'x'.repeat(513) + '"}' + koniec);
    assert.equal(g.destroyed, true);
    assert.equal(o.polaczenie, null);
  }
});

test("zamknięcie obcego oczekującego gniazda nie przerywa nadawania", async t => {
  const o = odbiornik(t), obce = new Gniazdo(), aktywne = new Gniazdo();
  o.przyjmij(obce);
  o.przyjmij(aktywne);
  aktywne.wyslij(naglowek);
  obce.emit("error", new Error("reset"));
  await zamkniete();
  assert.equal(o.polaczenie, aktywne);
  assert.equal(aktywne.destroyed, false);
});

test("dwa wcześniej otwarte gniazda nie przejmują sobie strumienia", async t => {
  const o = odbiornik(t), pierwsze = new Gniazdo(), drugie = new Gniazdo();
  o.przyjmij(pierwsze);
  o.przyjmij(drugie);
  pierwsze.wyslij(naglowek);
  drugie.wyslij(naglowek);
  await zamkniete();
  assert.equal(drugie.destroyed, true);
  assert.equal(o.polaczenie, pierwsze);
});

test("spóźnione close poprzedniego nadawcy nie kończy nowego", async t => {
  const o = odbiornik(t), stare = new Gniazdo(), nowe = new Gniazdo();
  o.przyjmij(stare);
  stare.wyslij(naglowek);
  o.rozlacz("zmiana nadawcy");
  o.przyjmij(nowe);
  nowe.wyslij(naglowek);
  await zamkniete();
  assert.equal(o.polaczenie, nowe);
});

test("oczekiwanie na nagłówek ma limit liczby i całkowitego czasu", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const o = odbiornik(t);
  const gniazda = Array.from({ length: 17 }, () => new Gniazdo());
  gniazda.forEach(g => o.przyjmij(g));
  assert.equal(gniazda[16].destroyed, true);
  t.mock.timers.tick(4000);
  gniazda[0].wyslij('{');
  t.mock.timers.tick(1000);
  await zamkniete();
  assert.ok(gniazda.every(g => g.destroyed));
  assert.equal(o.oczekujace.size, 0);
});

test("timeout logowania nie kończy uwierzytelnionego strumienia", t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const o = odbiornik(t), g = new Gniazdo();
  o.przyjmij(g);
  g.wyslij(naglowek);
  t.mock.timers.tick(6000);
  assert.equal(g.destroyed, false);
  assert.equal(o.polaczenie, g);
});
