// Minimalny klient WHEP (WebRTC-HTTP Egress Protocol) dla MediaMTX.
// Pobiera (odbiera) strumień z endpointu http://host:8889/<id>/whep.
//
// Podejście "non-trickle": zbieramy kandydatów ICE do końca (lub do timeoutu),
// po czym wysyłamy kompletną ofertę SDP. W sieci lokalnej kandydaci typu host
// w zupełności wystarczają, więc nie potrzebujemy STUN ani trickle/PATCH.
//
// Dołożone 2026-08-20: nagłówek uwierzytelniający. MediaMTX pyta nasz serwer,
// czy ten widz ma prawo oglądać (authMethod: http), a przedstawiamy się przez
// HTTP Basic — dok/DOSTEP_I_UZYTKOWNICY.md §6.

export class WhepClient {
  constructor(url, videoEl, { onState, onFatal, autoryzacja } = {}) {
    this.url = url;
    this.videoEl = videoEl;
    this.autoryzacja = autoryzacja || null;
    this.onState = onState || (() => {});
    this.onFatal = onFatal || (() => {});
    this.pc = null;
    this.resource = null;
    this.stopped = false;
  }

  async start() {
    this.stopped = false;
    this.onState("connecting");

    const pc = new RTCPeerConnection();
    this.pc = pc;

    pc.addTransceiver("video", { direction: "recvonly" });
    pc.addTransceiver("audio", { direction: "recvonly" });

    const stream = new MediaStream();
    pc.ontrack = (ev) => {
      stream.addTrack(ev.track);
      if (this.videoEl && this.videoEl.srcObject !== stream) {
        this.videoEl.srcObject = stream;
      }
    };

    pc.onconnectionstatechange = () => {
      if (this.stopped || this.pc !== pc) return;
      switch (pc.connectionState) {
        case "connected":
          this.onState("live");
          break;
        case "failed":
          this.onState("error");
          this.onFatal();
          break;
        case "disconnected":
          this.onState("reconnecting");
          break;
      }
    };

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await waitForIceGathering(pc, 2000);

    const naglowki = { "Content-Type": "application/sdp" };
    if (this.autoryzacja) naglowki.Authorization = this.autoryzacja;

    const res = await fetch(this.url, {
      method: "POST",
      headers: naglowki,
      body: pc.localDescription.sdp,
    });

    if (this.stopped) return;
    if (res.status === 401 || res.status === 403) {
      // Odmowa dostępu do obrazu to inna sprawa niż zerwane łącze — ponawianie
      // w kółko niczego nie naprawi, a widz musi zobaczyć powód.
      const e = new Error("Brak dostępu do obrazu");
      e.odmowa = true;
      throw e;
    }
    if (!res.ok) {
      throw new Error(`WHEP ${res.status} ${res.statusText}`);
    }

    const location = res.headers.get("Location");
    if (location) {
      this.resource = new URL(location, this.url).toString();
    }

    const answerSdp = await res.text();
    if (this.stopped) return;
    await pc.setRemoteDescription({ type: "answer", sdp: answerSdp });
  }

  async stop() {
    this.stopped = true;
    if (this.pc) {
      try { this.pc.close(); } catch { /* ignore */ }
      this.pc = null;
    }
    if (this.videoEl) {
      this.videoEl.srcObject = null;
    }
    if (this.resource) {
      // Uprzejmie zwolnij sesję po stronie serwera.
      try {
        await fetch(this.resource, {
          method: "DELETE",
          headers: this.autoryzacja ? { Authorization: this.autoryzacja } : {},
        });
      } catch { /* ignore */ }
      this.resource = null;
    }
  }
}

function waitForIceGathering(pc, timeoutMs) {
  if (pc.iceGatheringState === "complete") return Promise.resolve();
  return new Promise((resolve) => {
    const finish = () => {
      clearTimeout(timer);
      pc.removeEventListener("icegatheringstatechange", check);
      resolve();
    };
    const check = () => {
      if (pc.iceGatheringState === "complete") finish();
    };
    const timer = setTimeout(finish, timeoutMs);
    pc.addEventListener("icegatheringstatechange", check);
  });
}
