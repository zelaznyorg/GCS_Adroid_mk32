// Piktogramy klawiszy — odpowiednik `ui/Ikony.kt` z kokpitu.
//
// Kreska, nie wypełnienie; jeden styl na wszystkie (1,8 px, zaokrąglone końce).
// Klawisz ma piktogram NAD podpisem, bo w rękawicach i w słońcu kształt czyta się
// szybciej niż słowo — ale podpis zostaje, bo sam piktogram bywa zagadką (dok/UI.md §4).

const WSPOLNE = {
  width: 22,
  height: 22,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round",
  strokeLinejoin: "round",
  "aria-hidden": "true",
};

/** Pokrętło: okrąg z karbem i strzałką obrotu. */
export function IkonaPokretlo() {
  return (
    <svg {...WSPOLNE}>
      <circle cx="12" cy="12" r="8" />
      <path d="M12 4v3" />
      <path d="M18.5 7.5a8 8 0 0 1 1.4 4" />
      <path d="M19.9 11.5l1.3-1.3M19.9 11.5l-1.3-1.3" />
    </svg>
  );
}

export function IkonaMapa() {
  return (
    <svg {...WSPOLNE}>
      <path d="M9 4 L3 6 v14 l6-2 6 2 6-2V4l-6 2z" />
      <path d="M9 4v14" /><path d="M15 6v14" />
    </svg>
  );
}

/** Strzałka wychodząca z ramki — „wynieś to na inny ekran". */
export function IkonaOddokuj() {
  return (
    <svg {...WSPOLNE}>
      <path d="M14 4h6v6" /><path d="M20 4l-8 8" />
      <path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
    </svg>
  );
}

export function IkonaOko() {
  return (
    <svg {...WSPOLNE}>
      <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12Z" />
      <circle cx="12" cy="12" r="2.6" />
    </svg>
  );
}

export function IkonaKlucz() {
  return (
    <svg {...WSPOLNE}>
      <circle cx="8" cy="8" r="4" />
      <path d="M11 11 21 21" /><path d="M17.5 17.5 20 15" />
    </svg>
  );
}

export function IkonaStacja() {
  return (
    <svg {...WSPOLNE}>
      <rect x="3" y="4" width="18" height="6" rx="1" />
      <rect x="3" y="14" width="18" height="6" rx="1" />
      <path d="M7 7h.01" /><path d="M7 17h.01" />
    </svg>
  );
}

export function IkonaStrumien() {
  return (
    <svg {...WSPOLNE}>
      <rect x="2" y="6" width="13" height="12" rx="1" />
      <path d="m15 10 6-3.5v11L15 14" />
    </svg>
  );
}

export function IkonaPelnyEkran({ wlaczony = false }) {
  if (wlaczony) {
    return (
      <svg {...WSPOLNE}>
        <path d="M9 3v4a2 2 0 0 1-2 2H3" /><path d="M21 9h-4a2 2 0 0 1-2-2V3" />
        <path d="M3 15h4a2 2 0 0 1 2 2v4" /><path d="M15 21v-4a2 2 0 0 1 2-2h4" />
      </svg>
    );
  }
  return (
    <svg {...WSPOLNE}>
      <path d="M3 9V5a2 2 0 0 1 2-2h4" /><path d="M15 3h4a2 2 0 0 1 2 2v4" />
      <path d="M21 15v4a2 2 0 0 1-2 2h-4" /><path d="M9 21H5a2 2 0 0 1-2-2v-4" />
    </svg>
  );
}

/** Krzyżyk — zamknięcie okna podglądu na stacji. */
export function IkonaZamknij() {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}
