// Klient API sterującego MediaMTX (port 9997) — dodawanie/usuwanie ścieżek na żywo.
// Używa wbudowanego modułu http (zamiast global fetch), żeby działać też na Node 16
// (np. pakiet Node.js na starszym Synology DSM 7.1).
import http from "node:http";

const API = process.env.MTX_API || "http://127.0.0.1:9997";
const base = new URL(API);

function request(method, path, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = http.request(
      {
        hostname: base.hostname,
        port: base.port || 80,
        path,
        method,
        headers: data ? { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(data) } : {},
        timeout: 5000,
      },
      (res) => {
        let b = "";
        res.on("data", (d) => (b += d));
        res.on("end", () => resolve({ status: res.statusCode || 0, body: b }));
      }
    );
    req.on("error", reject);
    req.on("timeout", () => { req.destroy(); reject(new Error("timeout")); });
    if (data) req.write(data);
    req.end();
  });
}

const ok = (s) => s >= 200 && s < 300;

export async function isUp() {
  try {
    const r = await request("GET", "/v3/config/global/get");
    return ok(r.status);
  } catch {
    return false;
  }
}

export async function waitUntilUp(timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await isUp()) return true;
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

export async function listConfigPaths() {
  try {
    const r = await request("GET", "/v3/config/paths/list");
    if (!ok(r.status)) return [];
    return (JSON.parse(r.body).items || []).map((i) => i.name);
  } catch {
    return [];
  }
}

// Status runtime ścieżek (czy źródło jest gotowe / ile czytelników).
export async function pathsStatus() {
  try {
    const r = await request("GET", "/v3/paths/list");
    if (!ok(r.status)) return {};
    const out = {};
    for (const it of JSON.parse(r.body).items || []) {
      out[it.name] = {
        ready: Boolean(it.ready),
        readers: (it.readers || []).length,
        source: it.source?.type || null,
      };
    }
    return out;
  } catch {
    return {};
  }
}

export async function deletePath(name) {
  const r = await request("DELETE", `/v3/config/paths/delete/${encodeURIComponent(name)}`);
  // 404 = już nie istnieje — traktujemy jako sukces.
  return ok(r.status) || r.status === 404;
}

export async function upsertPath(name, conf) {
  // PATCH przed POST, a nie odwrotnie. Kolejność wygląda na drobiazg, ale nie jest:
  // ścieżka prawie zawsze już istnieje (powstaje z mediamtx.yml przy starcie), więc
  // próba dodania kończyła się wpisem `ERR [API] path already exists` w dzienniku
  // przy KAŻDEJ zmianie ustawień. Czerwony wpis, który znaczy „wszystko w porządku",
  // to dokładnie ten rodzaj śladu, przez który potem szuka się usterki godzinę.
  let r = await request("PATCH", `/v3/config/paths/patch/${encodeURIComponent(name)}`, conf);
  if (ok(r.status)) return true;
  // Nie ma jej jeszcze — dodaj.
  r = await request("POST", `/v3/config/paths/add/${encodeURIComponent(name)}`, conf);
  return ok(r.status);
}

// ---- sesje WebRTC ----
//
// Potrzebne do odcinania widzów. Samo unieważnienie żetonu zabiera stronę
// i telemetrię, ale NIE zrywa strumienia, który ktoś już trzyma otwarty —
// media lecą prosto z MediaMTX po UDP. Dopiero "kick" kończy sesję naprawdę.
// dok/DOSTEP_I_UZYTKOWNICY.md §6.

export async function webrtcSessions() {
  try {
    const r = await request("GET", "/v3/webrtcsessions/list");
    if (!ok(r.status)) return [];
    return (JSON.parse(r.body).items || []).map((s) => ({
      id: s.id,
      path: s.path,
      remoteAddr: s.remoteAddr || "",
      state: s.state,
      bytesSent: s.bytesSent,
      created: s.created,
    }));
  } catch {
    return [];
  }
}

export async function kickWebrtcSession(id) {
  try {
    const r = await request("POST", `/v3/webrtcsessions/kick/${encodeURIComponent(id)}`);
    return ok(r.status);
  } catch {
    return false;
  }
}

// Zrywa sesje pochodzące z danego adresu. Adres bierzemy stąd, że przy
// uwierzytelnianiu zewnętrznym MediaMTX podaje nam IP pytającego — kojarzymy
// je z żetonem i po tym trafiamy w sesję.
export async function kickPoAdresie(ips) {
  const zbior = new Set([...ips].filter(Boolean));
  if (!zbior.size) return 0;
  let ile = 0;
  for (const s of await webrtcSessions()) {
    const ip = String(s.remoteAddr).replace(/:\d+$/, "").replace(/^\[|\]$/g, "");
    if (zbior.has(ip) && (await kickWebrtcSession(s.id))) ile += 1;
  }
  return ile;
}
