# Router MikroTik — odczyt konfiguracji 2026-09-05

Odczyt z żywego routera po ssh (klucz laptopa wgrany 2026-09-05, tylko-odczyt).
To jest **stan**, nie plan — plan i uzasadnienia są w [ROUTER_MIKROTIK.md](ROUTER_MIKROTIK.md),
a między nimi są rozjazdy, spisane w §3. Druga seria zapytań (pula DHCP, serwer NTP,
listy interfejsów, stan łączy portów) **nie doszła** — sieć `.88` zniknęła spod laptopa
w trakcie; pola oznaczone ⚠ NIEODCZYTANE.

## 1. Sprzęt i system

| Cecha | Wartość |
|---|---|
| Model | **hEX lite, RB750r2 r3** (`SmarBox1`) |
| RouterOS | **7.24.1 stable** (2026-08-21), firmware 7.24.1 |
| CPU / RAM / flash | MIPS 24Kc 850 MHz, 1 rdzeń · 64 MB (30 MB wolne) · 16 MB (4,5 MB wolne) |
| Radio | **BRAK** — sam Ethernet, 5 portów |
| WireGuard | dostępny (RouterOS 7), **nieskonfigurowany** — `/interface/wireguard` puste |
| Zegar | Europe/Warsaw, **NTP klient zsynchronizowany** z `europe.pool.ntp.org` (stratum 2, offset −3 ms) |
| DDNS | `/ip/cloud ddns-enabled=auto` |

**Wniosek o zegarze:** router MA poprawny czas i ma internet. TODO 2.43 (stacja bez
źródła czasu) da się domknąć **bez sprzętu**: włączyć na routerze serwer NTP
(`/system/ntp/server set enabled=yes`) i wskazać stacji `192.168.88.1` jako źródło
czasu. ⚠ Czy serwer NTP jest już włączony — NIEODCZYTANE (`/system/ntp/server/print`
nie doszło); w `/ip/service` widnieje dynamiczny wpis `ntp 123 udp`, co na to wskazuje.

## 2. Sieć

```
 internet ──► ether1 (WAN, DHCP: 192.168.1.117/24, brama 192.168.1.2)   ← podwójny NAT
                │
             hEX lite  192.168.88.1
                │
    bridge1 = ether2 + ether3 + ether4      192.168.88.0/24  „DRON15 LAN"
                │        └─ ether3: 192.168.88.199 PLUSPL-W21C (jedyna dzierżawa DHCP)
                └─ stacja .30 (adres statyczny, poza DHCP)
             ether5  192.168.144.254/24  „DRON15 brama sieci pokladowej"  — ŁĄCZE NIEAKTYWNE
```

| Element | Stan |
|---|---|
| WAN | `ether1`, klient DHCP, `192.168.1.117`, trasa domyślna przez `192.168.1.2` — **za drugim routerem** (dom), czyli podwójny NAT |
| LAN | `bridge1` (ether2–4), `192.168.88.1/24`, DHCP `dhcp1`, pula `dhcp-88` (zakres ⚠ NIEODCZYTANY), dzierżawa 1 h, DNS = router |
| Sieć pokładowa | `ether5` z adresem `192.168.144.254/24`, **łącze nieaktywne** (nic wpięte w chwili odczytu) |
| Klienci | jedna dzierżawa: `192.168.88.199`, `BC:E9:2F:F9:30:E3`, host `PLUSPL-W21C`, na `ether3` |
| DNS | 1.1.1.1, 8.8.8.8, `allow-remote-requests=yes`; **`mdns-repeat-ifaces` puste** |

⚠ **`PLUSPL-W21C` — do rozpoznania.** Nazwa hosta wygląda na urządzenie operatora Plus
(router LTE / modem z Wi‑Fi). Jeśli to router LTE **w trybie routera**, wpięty w `ether3`,
to każde urządzenie za jego Wi‑Fi widać ze stacji jako **jeden adres `.199`**, a rozgłoszenia
i mDNS nie przechodzą — to przekreśla wykrywanie stacji z kontrolera. W trybie mostu
(bridge/AP) problemu nie ma. Do sprawdzenia fizycznie.

## 3. Firewall — stan i rozjazd z planem

Łańcuch `input`: powroty, drop invalid, ICMP z każdej sieci, **pełny dostęp z `bridge1`**,
reszta drop. Łańcuch `forward`: fasttrack, powroty, drop invalid, `bridge1 → ether5`,
`ether5 → bridge1`, `LAN → WAN` (listy interfejsów; skład list ⚠ NIEODCZYTANY), reszta drop.
NAT: masquerade na WAN **oraz masquerade `→ ether5`** (stacja widzi sieć pokładową spod
adresu routera `.144.254`). Usługi: ssh i winbox tylko z `192.168.88.0/24`; ftp, telnet,
www, api wyłączone. Brak `dst-nat` — nic nie jest wystawione do internetu. ✅

| Temat | Plan (ROUTER_MIKROTIK.md) | Stan | Skutek |
|---|---|---|---|
| adres routera | `192.168.144.1`, jedna sieć | `192.168.88.1` + osobna `.144.254/24` na ether5 | **dwie sieci, z NAT między nimi** — dokładnie wariant, który §1 planu odrzucił |
| stacja | `192.168.144.30` | `192.168.88.30` | wszystkie reguły z planu (§5.2) celują w zły adres |
| WireGuard | serwer na routerze, `10.20.15.0/24`, UDP 51820 | **nie ma** | dostęp zdalny dziś nie istnieje |
| WAN | publiczny adres | `192.168.1.117` za domowym routerem | serwer WireGuard na hEX **nie przyjmie połączenia**, dopóki dom nie przekieruje UDP 51820 na `.1.117` — albo serwer staje na routerze domowym (plan §0) |
| NTP | „router nie odpowiada na NTP" (TODO 2.43) | klient zsynchronizowany | do domknięcia programowo, §1 |

**Podwójny NAT sieci pokładowej ma jeden dobry skutek:** telemetria z MK32 i obraz z ZR30
wchodzą do `.88` przez router, a **z `.88` do `.144` idzie wyłącznie ruch nawiązany od
strony stacji** (masquerade + reguła `bridge1 → ether5`). Czyli to, czego plan §1.2
się bał — brak odgrodzenia drona — w praktyce jest **odgrodzone w jedną stronę**:
z sieci pokładowej nikt nie nawiąże połączenia do stacji ani do telefonów. W drugą
stronę (LAN → pokładowa) reguła jest **szeroka** — każdy w `.88` sięgnie ZR30 na porcie
SDK. Zawęzić do `.88.30` i portów kamery, gdy w `.88` pojawią się telefony gości (§4).

## 4. Co z tego wynika dla parowania telefonem (rozmowa 2026-09-04/05)

1. **Radia nie ma.** Telefon i kontroler DJI muszą dostać Wi‑Fi z osobnego urządzenia.
   Kandydat już wisi na `ether3` (`PLUSPL-W21C`) — rozstrzygnąć tryb (most / router).
   Jeśli router: przełączyć w most albo zaakceptować, że wykrywanie stacji nie działa
   i kod parowania musi nieść adres.
2. **Wykrywanie stacji przez mDNS / rozgłoszenie** działa tylko w jednej domenie L2.
   Dziś `.88` jest płaskie (bridge1), więc w LAN działa. `mdns-repeat-ifaces` na routerze
   istnieje (RouterOS 7.24), gdyby telefony trafiły do osobnego VLAN-u.
3. **Osobna sieć dla gości** (VLAN na bridge1 albo `ether4` poza mostem) + reguły
   `forward` tylko do `.88.30` na 8095, 8889, 8189/udp, 5601, 1935. hEX lite to udźwignie —
   ruch obrazu z telefonów to pojedyncze megabity. ⚠ Odbiera to gościom trasę do `.144`
   (kamera SDK, kontroler lotu) — i o to chodzi.
4. **Zegar:** żetony parowania z terminem ważności wymagają czasu na stacji — NTP z routera
   (§1) jest warunkiem wstępnym i jest na wyciągnięcie ręki.
5. **Dostęp zdalny** do parowania (telefon na LTE) wymaga WireGuarda, którego nie ma,
   a hEX stoi za drugim NAT-em. Etap na później; parowanie w polu odbywa się w LAN.
6. **Rate-limit prób parowania** to sprawa stacji, nie routera. Router może dołożyć
   `/ip/firewall/filter` z `limit` na 8095 z sieci gości, ale to zapas, nie podstawa.

## 5. Czego NIE zrobiono

Na routerze **nic nie zmieniono**. Wszystkie polecenia były `print`. Klucz publiczny
laptopa (`dron15-stacja`) wgrał Tom sam, dwoma poleceniami `scp` + `/user/ssh-keys/import`.
