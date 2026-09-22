// Nośniki wymienne: co uznajemy za pendrive i co wolno na nim zapisać.
//
// ⛔ Ten moduł pisze na nośnik z żądania HTTP, więc dwie rzeczy muszą być pewne:
// ścieżka pochodzi z listy systemu (nie z żądania), a nazwa pliku jest PRZEPISANA,
// nie „oczyszczona". Reszta to wygoda.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const logi = mkdtempSync(join(tmpdir(), "panorama-nosniki-test-"));
process.env.LOGI_DIR = logi;
process.env.POZIOM = "blad";
const { parsujMounts, bezpiecznaNazwa, zDataUrl, zapisz, KATALOG_NOSNIKOW } = await import("../server/nosniki.mjs");
process.on("exit", () => rmSync(logi, { recursive: true, force: true }));

// Prawdziwy kształt /proc/mounts z maliny: karta systemowa, nagrywarka CVBS
// i dwa pendrive'y zamontowane przez pomocnika pulpitu.
const MOUNTS = [
  "/dev/mmcblk0p2 / ext4 rw,noatime 0 0",
  "/dev/mmcblk0p1 /boot/firmware vfat rw,relatime 0 0",
  "/dev/mmcblk2p1 /media/fpv-recordings exfat rw,noatime 0 0",
  "/dev/sda1 /media/gcs/KINGSTON vfat rw,nosuid,nodev,relatime 0 0",
  "/dev/sdb1 /media/gcs/DYSK\\040POLOWY exfat rw,nosuid,nodev 0 0",
  "/dev/sdb2 /media/gcs/KINGSTON/podkatalog vfat rw 0 0",
  "",
].join("\n");

test("za nośnik uznajemy tylko to, co leży wprost w katalogu pulpitu", () => {
  const n = parsujMounts(MOUNTS, "/media/gcs");
  assert.deepEqual(n.map((x) => x.nazwa), ["KINGSTON", "DYSK POLOWY"]);
  // Spacja przyjeżdża jako ósemkowe \040 — bez odkodowania ścieżka nie istnieje.
  assert.equal(n[1].sciezka, "/media/gcs/DYSK POLOWY");
  assert.equal(n[0].system, "vfat");
  // Karta systemowa i nagrywarka CVBS to nie pendrive'y, a katalog NA nośniku
  // nie jest osobnym nośnikiem.
  assert.equal(n.some((x) => x.sciezka.includes("fpv-recordings")), false);
  assert.equal(n.some((x) => x.sciezka.endsWith("podkatalog")), false);
});

test("pusty albo nieznany /proc/mounts nie wywraca listy", () => {
  assert.deepEqual(parsujMounts("", "/media/gcs"), []);
  assert.deepEqual(parsujMounts("śmieci bez pól", "/media/gcs"), []);
  assert.deepEqual(parsujMounts(MOUNTS, "/nie/ma/takiego"), []);
});

test("nazwa pliku jest przepisywana, nie oczyszczana", () => {
  assert.equal(bezpiecznaNazwa("zaproszenie-Tom.png"), "zaproszenie-Tom.png");
  // ⛔ Próba wyjścia z nośnika kończy się samą nazwą pliku.
  assert.equal(bezpiecznaNazwa("../../etc/passwd"), "passwd");
  assert.equal(bezpiecznaNazwa("/etc/shadow"), "shadow");
  assert.equal(bezpiecznaNazwa("zaproszenie dla Anny.png"), "zaproszenie-dla-Anny.png");
  assert.equal(bezpiecznaNazwa("zaproszenie-Łukasz.png"), "zaproszenie-Lukasz.png");
  assert.equal(bezpiecznaNazwa("...", "domyslna.png"), "domyslna.png");
  assert.equal(bezpiecznaNazwa("", "domyslna.png"), "domyslna.png");
  assert.equal(bezpiecznaNazwa("a".repeat(200)).length, 64);
});

test("przyjmujemy wyłącznie PNG w postaci data URL", () => {
  const png = zDataUrl("data:image/png;base64,iVBORw0KGgo=");
  assert.ok(Buffer.isBuffer(png) && png.length > 0);
  for (const zle of [
    "data:image/svg+xml;base64,PHN2Zz4=",
    "data:text/html;base64,PGh0bWw+",
    "iVBORw0KGgo=",
    "",
    null,
  ]) {
    assert.throws(() => zDataUrl(zle), /PNG/);
  }
});

test("zapis na nośnik, którego system nie melduje, jest odmawiany", async () => {
  // Na maszynie deweloperskiej /proc/mounts nie istnieje, więc lista jest pusta —
  // i to jest właśnie przypadek „nie ma pendrive'a".
  await assert.rejects(
    zapisz({ nosnik: "/media/gcs/KINGSTON", nazwa: "kod.png", dane: Buffer.from("x") }),
    (e) => e.message.includes(KATALOG_NOSNIKOW),
  );
  await assert.rejects(
    zapisz({ nosnik: "/etc", nazwa: "kod.png", dane: Buffer.from("x") }),
    /pendrive|nośnika/,
  );
});
