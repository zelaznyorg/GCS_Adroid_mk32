# Router MikroTik — konfiguracja pod stację naziemną

Router: **MikroTik, dziś na 192.168.88.1** (domyślna adresacja RouterOS).
Realizuje **decyzję 5** z [../PLAN.md](../PLAN.md) §10: dostęp zdalny do stacji
wyłącznie przez **WireGuard na routerze**. Bez Tailscale'a, bez VPS-a.

> ### ✅ DECYZJA 2026-08-28 (Tom): JEDNA SIEĆ — `192.168.144.0/24`
>
> Zamiast dwóch podsieci (domowej `192.168.88.0/24` i pokładowej `192.168.144.0/24`)
> **router obsługuje jedną, płaską sieć `192.168.144.0/24`** — tę samą, w której
> siedzi sprzęt SIYI. Sieć `192.168.88.x` znika.
>
> **Uzasadnienie w §1.** Cena decyzji, świadoma, też w §1 — i jest realna.

Router ma zrobić dokładnie cztery rzeczy — nic ponadto:

| # | Zadanie | Dlaczego |
|---|---|---|
| 1 | **jedna sieć `192.168.144.0/24`** na wszystkich portach LAN | §1 — sprzęt SIYI i sprzęt domowy widzą się wprost |
| 2 | **rozdawać DHCP** i **uciszyć DHCP MK32** | §3 — inaczej adresacja domu zależy od tego, czy dron jest pod napięciem |
| 3 | **serwer WireGuard** na UDP 51820 | §4 — jedyna droga wejścia z zewnątrz |
| 4 | **wpuścić z tunelu tylko do stacji**, na trzy porty | §5.2 — widz ogląda obraz, nie dotyka drona |

⛔ **Portów stacji NIE przekierowywać z internetu** (`dst-nat`). Wejście ma być
wyłącznie przez tunel — inaczej panel administratora i sterowanie kamerą stoją
otworem dla świata.

---

## 0. Zanim cokolwiek — czy ten router w ogóle jest osiągalny z zewnątrz

**To jest warunek konieczny i nie da się go obejść po naszej stronie.** Serwer
WireGuard przyjmuje połączenia, więc router musi mieć **publiczny adres**. Za CGNAT
operatora nie zadziała nic z rozdziału o tunelu — [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.

Na routerze:

```
/ip/address/print
/ip/cloud/print
```

Porównaj adres na interfejsie WAN z tym, co `/ip/cloud` pokazuje jako
**Public Address**. Rozstrzygnięcie:

| Adres na WAN | Wniosek |
|---|---|
| równy publicznemu z `/ip/cloud` | ✅ publiczny — idź dalej |
| `100.64.x`–`100.127.x` | ⛔ **CGNAT operatora** — WireGuard jako serwer nie zadziała |
| `10.x`, `172.16–31.x`, `192.168.x` | ⛔ router siedzi za innym routerem albo za CGNAT |

Przy CGNAT są dwa wyjścia i oba są poza tą decyzją: publiczny adres od operatora
(zwykle płatny dodatek) albo zmiana warstwy tunelu na Tailscale/VPS —
[WIDEO.md](WIDEO.md) §61.

⚠ **Adres publiczny bywa zmienny.** Stacja pokazuje go sama w panelu administratora,
sekcja ŁĄCZA I ADRESY (decyzja 6) — operator przepisuje endpoint do klienta ręcznie.
Alternatywa po stronie routera: `/ip/cloud set ddns-enabled=yes`, wtedy endpointem
jest stała nazwa `xxxxxxxx.sn.mynetname.net` zamiast zmiennego adresu. **To jest
prostsze niż przepisywanie liczb i warto to włączyć.**

⚠ WireGuard w RouterOS wymaga **wersji 7.x**. Kontrola: `/system/resource/print`.
DHCP snooping z §3 również jest funkcją RouterOS 7.

### 0a. Zmierzone na laptopie 2026-08-28 — trzy fakty, zanim ktoś zacznie szukać usterki

| Pomiar | Wynik |
|---|---|
| `ping 192.168.88.1` | **brak odpowiedzi**, w tablicy ARP **żadnego wpisu** dla `.88.1` |
| adapter `Ethernet 8` (ten z adresem `192.168.88.254`) | **Disconnected, 0 bps** |
| skąd `192.168.88.254` | `PrefixOrigin: Dhcp` — **stara dzierżawa**, trzymana na wyłączonym łączu |

**To nie jest usterka routera.** Kabel do MikroTika jest odpięty (albo router
wyłączony), a Windows wciąż pokazuje adres z poprzedniej dzierżawy. Cisza na
warstwie drugiej — brak ARP — wyklucza firewall jako wyjaśnienie: router zawsze
odpowiada na ARP, nawet gdy blokuje ping.

> ### ⛔ MikroTik NIE jest tu bramą do internetu — trasa domyślna idzie gdzie indziej
>
> W tablicy tras laptopa są **dwie** trasy domyślne:
>
> | Brama | Interfejs | Metryka |
> |---|---|---|
> | **192.168.1.2** | `192.168.1.51` | **25 — wygrywa** |
> | 192.168.88.1 | 192.168.88.254 | 35 |
>
> Czyli w tej lokalizacji ruch do świata wychodzi przez urządzenie **192.168.1.2**,
> a MikroTik jest siecią boczną. **Jeśli MikroTik sam siedzi za `192.168.1.2`,
> to jest podwójny NAT i serwer WireGuard na nim nie przyjmie połączenia**, dopóki
> `192.168.1.2` nie przekieruje na niego UDP 51820 — a i to tylko wtedy, gdy sam ma
> adres publiczny.
>
> **Serwer WireGuard stawia się na tym urządzeniu, które trzyma publiczny adres** —
> nie na tym, które jest wygodniejsze. Ta sprawa jest **niezależna od decyzji o jednej
> sieci** i trzeba ją rozstrzygnąć osobno: `/ip/address/print` na MikroTiku.

> ### ⚠ Na laptopie jest NordVPN — dokładnie pułapka z [SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.5
>
> Wykryte adaptery: `NordLynx` i `OpenVPN Data Channel Offload for NordVPN`
> (oba w tej chwili **Disconnected**, więc nic nie psują teraz).
>
> Przy włączonym NordVPN-ie sprawdzanie „jaki mam adres publiczny" pokaże **adres
> wyjściowy Norda**, a połączenia przychodzące i tak nie zadziałają. Adres odczytany
> w ten sposób ma sens **wyłącznie wtedy, gdy ruch wychodzi wprost przez router**.
> Przy pomiarach adresu publicznego NordVPN wyłączyć.

---

## 1. Dlaczego jedna sieć, i co to kosztuje

### 1.1 Co znika razem z drugą podsiecią

Wariant dwóch podsieci wymagał trzech rzeczy, z których **każda jest osobnym
źródłem awarii**, i wszystkie trzy teraz odpadają:

| Co odpada | Dlaczego było potrzebne | Czym groziło |
|---|---|---|
| **maskarada `srcnat`** | sprzęt SIYI nie ma trasy powrotnej do `192.168.88.0/24` | bez niej `ping` czasem chodzi, a **TCP wisi bez błędu** — objaw mylący |
| **trasowanie między podsieciami** | dwie sieci muszą się jakoś widzieć | reguły `forward`, kolejność, łatwo o cichy `drop` |
| **stacja w dwóch sieciach naraz** | RPi 5 ma jeden Ethernet, a sieci były dwie | Wi-Fi jako drugi interfejs + ryzyko, że DHCP z pokładu wepchnie trasę domyślną |

Płaska sieć `192.168.144.0/24` znosi to wszystko **z definicji**: każdy z każdym
rozmawia wprost, na jednym kablu, bez pośrednika. Adresacja jest przy tym ta sama,
której używa SIYI (`..\..\CLAUDE.md` §4), więc **po stronie drona nie zmienia się nic** —
kamera zostaje `192.168.144.25`, aparatura `.12`, kokpit `.20`.

Stacji wystarcza **jeden interfejs** — zwykły Ethernet do MikroTika.

### 1.2 ⛔ Cena: znika możliwość odgrodzenia drona od reszty sieci

**To jest jedyna realna strata i wynika z fizyki, nie z konfiguracji.** Router
filtruje wyłącznie ruch, który przez niego **przechodzi**. W płaskiej sieci dwa
komputery na tym samym przełączniku rozmawiają **bez udziału routera** — on tego
ruchu nigdy nie widzi, więc nie może go ani zobaczyć, ani zatrzymać.

Praktycznie znaczy to, że **każde urządzenie wpięte do tej sieci** może:

| Zasób | Ograniczenie sprzętu | Co zrobi dowolny host w sieci |
|---|---|---|
| `.12:19856` telemetria | **jeden klient MAVLink** | **podbierze telemetrię kokpitowi** |
| `.25:37256` obraz SIYI | **jeden klient** | zabierze obraz; przy szarpaniu **zawiesi kamerę do cyklu zasilania** |
| `.12:19856` w górę | **brak uwierzytelniania** | **zapisze parametr w kontrolerze lotu** |

⛔ **Ostatni wiersz jest najpoważniejszy.** Narzędzia tego projektu potrafią pisać
do FC po tej drodze:

```
py -3 tools\fc_write_params.py --port udpout:192.168.144.12:19856 …
```

W płaskiej sieci **nie da się tego zablokować regułą na routerze**. Ochroną jest
wyłącznie to, **kogo się do tej sieci wpuszcza**.

### 1.3 Kiedy ta cena jest do przyjęcia — i kiedy nie

| Sytuacja | Werdykt |
|---|---|
| do sieci wpinasz **tylko swój sprzęt** (stacja, laptop, dron) | ✅ **jedna sieć — prostsza i lepsza** |
| w tej samej sieci stoi **Wi-Fi dla gości**, telewizor, sprzęt IoT | ⛔ wróć do dwóch podsieci albo **wydziel VLAN** |

**Rekomendacja: jedna sieć**, przy zasadzie „w tej sieci stoi tylko sprzęt projektu".
Gdyby kiedyś doszło Wi-Fi dla gości, właściwym narzędziem jest **osobny VLAN dla
gości**, a nie powrót do maskarady.

### 1.4 Co zostaje chronione mimo płaskiej sieci

✅ **Granica wobec tunelu zostaje.** Ruch z WireGuarda **jest** trasowany — wchodzi
z `wg0` do LAN-u przez router, więc router go widzi i filtruje. Reguły z §5.2 są
w pełni skuteczne: **zdalny widz dochodzi do stacji, na trzy porty, i do drona
nie ma trasy.**

Innymi słowy: tracimy filtrowanie **wewnątrz domu**, zachowujemy filtrowanie
**z zewnątrz**. Ta druga granica jest ważniejsza.

---

## 2. Adresacja

| Rola | Adres | Skąd |
|---|---|---|
| **router (brama)** | **`192.168.144.1`** | statycznie; ⚠ przed wpisaniem sprawdzić, że nikt tam nie siedzi |
| MK32 air unit | `.11` | stałe, SIYI |
| MK32 ground unit | `.12` | stałe, SIYI |
| MK32 Android (kokpit) | `.20` | stałe, SIYI |
| **ZR30** | **`.25`** | stałe, SIYI |
| slot kamery 2 | `.26` | wolny |
| **stacja RPi 5** | **`.30`** | rezerwacja DHCP; adres zalecany przez SIYI dla komputera GCS |
| konwerter HDMI / AI Camera | `.50` / `.60` | opcje |
| **pula DHCP MikroTika** | **`.200`–`.249`** | §3 — celowo poza wszystkim powyżej |
| tunel WireGuard | `10.20.15.0/24` | router `10.20.15.1`, klienci od `.11` |

⚠ **`192.168.144.1` — sprawdzić, czy jest wolny**, zanim się go wpisze. Mapa sieci
SIYI (`..\..\CLAUDE.md` §4) go nie wymienia, ale nie wymienia też wprost bramy.
Z laptopa wpiętego w sieć pokładową: `ping 192.168.144.1` ma **milczeć**.
Gdyby odpowiadał — router bierze `.2`, a cały dokument czyta się z tą podmianą.

⚠ **Pula `.200`–`.249` jest wybrana z rozmysłu.** Laptop dostał kiedyś od SIYI
adres `.161`, czyli pula MK32 sięga co najmniej tam. Trzymamy się wyżej, żeby
przy ewentualnym powrocie DHCP MK32 (awaria reguły z §3) **nie doszło do przydzielenia
tego samego adresu dwa razy**.

```
/ip/address
add address=192.168.144.1/24 interface=bridge comment="DRON15 brama"
```

Nazwa mostu (`bridge`) do sprawdzenia: `/interface/bridge/print`. Stara adresacja
`192.168.88.0/24` znika — usunąć jej adres, pulę i serwer DHCP, żeby nie zostały
dwa zestawy reguł, z których jeden nic nie robi.

---

## 3. DHCP — rozdaje MikroTik, MK32 zostaje uciszony

### 3.1 Dlaczego akurat tak, a nie odwrotnie

Kusi, żeby po prostu zostawić DHCP po stronie MK32 i nie stawiać własnego.
**To jest zła droga i ma jeden konkretny powód:**

⛔ **Serwer DHCP w MK32 znika razem z wyłączeniem aparatury.** Gdyby był jedynym,
to **cała sieć domowa traciłaby adresację, gdy dron nie jest pod napięciem** —
laptop bez adresu, stacja bez adresu, i wszystko to bez związku z lataniem.

Dlatego: **DHCP rozdaje router**, bo router stoi zawsze. DHCP z MK32 zostaje
zagłuszony — nie wyłączony w samej aparaturze (nie mamy tam takiego ustawienia),
tylko **zatrzymany na porcie, w który jest wpięta**.

⚠ Sprzęt SIYI to przeżyje, bo **wszystkie jednostki SIYI mają adresy stałe**
(`.11`, `.12`, `.20`, `.25` — `..\..\CLAUDE.md` §4). DHCP w MK32 obsługuje gości,
nie własny sprzęt. **Zweryfikować po włączeniu reguły:** kokpit ma nadal widzieć
telemetrię i obraz.

### 3.2 Serwer DHCP na routerze

```
/ip/pool
add name=dron15-pool ranges=192.168.144.200-192.168.144.249

/ip/dhcp-server
add name=dron15-dhcp interface=bridge address-pool=dron15-pool disabled=no

/ip/dhcp-server/network
add address=192.168.144.0/24 gateway=192.168.144.1 dns-server=192.168.144.1 \
    comment="DRON15"
```

Rezerwacja dla stacji — MAC odczytać z `/ip/dhcp-server/lease/print` po jej
pierwszym połączeniu albo na samej stacji przez `ip link`:

```
/ip/dhcp-server/lease
add address=192.168.144.30 mac-address=AA:BB:CC:DD:EE:FF server=dron15-dhcp \
    comment="DRON15 stacja RPi5"
```

### 3.3 Uciszenie DHCP z MK32 — DHCP snooping

RouterOS 7 ma na to funkcję wprost: most przepuszcza odpowiedzi DHCP **tylko
z portów zaufanych**, a port z aparaturą zaufany nie jest.

```
/interface/bridge
set [find name=bridge] dhcp-snooping=yes

/interface/bridge/port
set [find interface=ether5] trusted=no comment="DRON15: MK32 nie rozdaje adresow"
```

`ether5` podmienić na port, w który faktycznie wchodzi kabel z MK32.

⚠ Własny serwer DHCP routera działa mimo snoopingu — siedzi na interfejsie mostu,
nie na porcie. Zapytania klientów (port źródłowy 68) przechodzą normalnie;
blokowane są wyłącznie **odpowiedzi serwera** (port źródłowy 67) z portów niezaufanych.

**Gdyby snooping był niedostępny** (starsze RouterOS-y), to samo filtrem mostu:

```
/interface/bridge/filter
add chain=forward action=drop in-interface=ether5 mac-protocol=ip \
    protocol=udp src-port=67 dst-port=68 comment="DRON15: cisza DHCP od MK32"
```

**Kontrola, że zadziałało:** laptop na DHCP ma dostać adres z zakresu
`.200`–`.249`, a `ipconfig /all` ma pokazać **DHCP Server = 192.168.144.1**.
Jeśli pokazuje coś innego — reguła nie działa i **działają dwa serwery DHCP naraz**.

---

## 4. Serwer WireGuard

```
/interface/wireguard
add name=wg0 listen-port=51820 mtu=1420 comment="DRON15 dostep zdalny"

/ip/address
add address=10.20.15.1/24 interface=wg0 comment="DRON15 tunel"
```

**Klucz publiczny routera** (potrzebny każdemu klientowi):

```
/interface/wireguard/print detail
```

Każdy klient dostaje **własną parę kluczy i własny adres** w `10.20.15.0/24`.
Klucz prywatny klienta nigdy nie trafia na router — router dostaje tylko publiczny:

```
/interface/wireguard/peers
add interface=wg0 public-key="<KLUCZ_PUBLICZNY_KLIENTA>" \
    allowed-address=10.20.15.11/32 comment="laptop-tom"
```

⚠ `allowed-address` na routerze to **`/32` konkretnego klienta**, nie `0.0.0.0/0`.
Wpisanie tam szerokiego zakresu każe routerowi wysyłać do tego peera cały ruch.

---

## 5. Firewall

### 5.1 Wejście tunelu

```
/ip/firewall/filter
add chain=input action=accept protocol=udp dst-port=51820 \
    comment="DRON15: WireGuard wejscie"
```

⛔ **Reguła musi stanąć NAD `defconf: drop all not coming from LAN`**, inaczej nie
robi nic. Dodana trafia na koniec listy, więc trzeba ją przesunąć:

```
/ip/firewall/filter
move [find comment="DRON15: WireGuard wejscie"] \
     destination=[find where comment~"defconf: drop all not coming from LAN"]
```

Gdy powyższe nie zadziała (starsze RouterOS-y bywają wybredne o `move`), to samo
w Winboksie: **IP → Firewall → Filter Rules**, przeciągnąć regułę myszą nad
`defconf: drop all not coming from LAN`.

Kontrola po fakcie: `/ip/firewall/filter/print` — kolejność ma być widoczna
w numerach, nie w komentarzach.

### 5.2 Z tunelu wolno tylko do stacji, tylko na trzy porty

**To jest granica, która w płaskiej sieci nadal działa** (§1.4) — ruch z `wg0`
przechodzi przez router, więc router go filtruje.

```
/ip/firewall/filter
add chain=forward action=accept in-interface=wg0 dst-address=192.168.144.30 \
    protocol=tcp dst-port=8095,8889 comment="DRON15: tunel -> stacja TCP"
add chain=forward action=accept in-interface=wg0 dst-address=192.168.144.30 \
    protocol=udp dst-port=8189 comment="DRON15: tunel -> stacja media"
add chain=forward action=drop in-interface=wg0 \
    comment="DRON15: tunel nigdzie indziej"
```

Te trzy przesunąć **na początek łańcucha `forward`**, zachowując ich kolejność
względem siebie (drop **na końcu tej trójki**, nie przed akceptacjami).

⛔ **Nie wpisywać `wg0` do listy interfejsów LAN.** To najkrótsza droga do dania
zdalnemu widzowi dostępu do kontrolera lotu (§1.2) — a widz ma widzieć obraz.

⛔ **Sieci pokładowej nie wystawiać do tunelu.** Reguły wyżej celowo kończą się
na `192.168.144.30` — samej stacji. Adresy `.11`, `.12`, `.20`, `.25` nie mają
z tunelu żadnej trasy i mieć nie mają.

Port **9997 (API MediaMTX) zostaje wyłącznie lokalny** — nie ma go w żadnej regule
i tak ma być ([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.2).

---

## 6. Konfiguracja klienta

`wg-quick` / aplikacja WireGuard na laptopie lub telefonie:

```ini
[Interface]
PrivateKey = <klucz prywatny klienta>
Address    = 10.20.15.11/32
MTU        = 1420

[Peer]
PublicKey           = <klucz publiczny routera z /interface/wireguard/print detail>
AllowedIPs          = 192.168.144.30/32, 10.20.15.0/24
Endpoint            = <adres publiczny albo nazwa z IP Cloud>:51820
PersistentKeepalive = 25
```

`AllowedIPs` celowo wąskie: klient wysyła w tunel **wyłącznie** ruch do stacji.
Reszta jego internetu idzie normalnie — tunel nie spowalnia niczego innego.
Wpisanie tam `192.168.144.0/24` **byłoby błędem** — otworzyłoby zdalnemu klientowi
drogę do drona.

Po zestawieniu tunelu strona stacji jest pod **`http://192.168.144.30:8095`**.
Adresu WHEP nie trzeba nigdzie wpisywać — `App.jsx` wyprowadza go z adresu strony
([SERWER_PODGLADU.md](SERWER_PODGLADU.md) §6.4).

---

## 7. MTU — jedyna pułapka, która myli wszystkich

**Objaw: telemetria i strona działają bez zarzutu, a obrazu nie ma albo się rozsypuje.**
Kto tego nie wie, szuka błędu w WebRTC i traci wieczór — to jest MTU.

WireGuard dokłada ok. 60 B narzutu. Domyślne 1420 zwykle wystarcza, ale pod **LTE**
(MTU ~1430) albo **PPPoE** (1492) trzeba zejść niżej:

```
/interface/wireguard set [find name=wg0] mtu=1280
```

i to samo `MTU = 1280` po stronie klienta. `rpi/sprawdz.sh` na stacji wypisuje MTU
wszystkich interfejsów i **ostrzega przy `wg0` powyżej 1420**
([WDROZENIE_RPI.md](WDROZENIE_RPI.md) §7.2).

---

## 8. Kontrola po konfiguracji

| Co | Polecenie | Oczekiwane |
|---|---|---|
| brama nie koliduje | z sieci przed zmianą: `ping 192.168.144.1` | **cisza** — dopiero wtedy wpisywać §2 |
| **DHCP rozdaje router** | na kliencie: `ipconfig /all` | adres `.200`–`.249`, **DHCP Server = 192.168.144.1** |
| **MK32 nie rozdaje** | jw., przy włączonej aparaturze | **ten sam wynik** — inaczej reguła z §3.3 nie działa |
| stacja pod stałym adresem | `/ip/dhcp-server/lease/print` | `192.168.144.30`, dynamic=no |
| sprzęt drona widoczny | z laptopa: `ping 192.168.144.25` | odpowiedzi z ZR30 |
| obraz z kamery | `ffplay rtsp://192.168.144.25:8554/main.264` | strumień; RTSP znosi wielu widzów |
| peer się zestawił | `/interface/wireguard/peers/print detail` | niezerowe `rx`/`tx`, świeży `last-handshake` |
| reguła input działa | `/ip/firewall/filter/print stats` | rosnące liczniki na „WireGuard wejscie" |
| strona przez tunel | przeglądarka: `http://192.168.144.30:8095` | panel stacji |
| **obraz przez tunel** | ten sam adres, podgląd | ⚠ jeśli tu pada, a strona żyje — **MTU, §7** |
| **tunel nie widzi drona** | z klienta WG: `ping 192.168.144.25` | **brak odpowiedzi to dobrze** — §5.2 |
| **tunel nie widzi routera** | z klienta WG: `ping 192.168.144.1` | **brak odpowiedzi to dobrze** |

---

## 9. Reset routera do ustawień fabrycznych

Potrzebny 2026-08-28: hasło nie zostało przyjęte i nie ma jak się zalogować.
`/system/reset-configuration` odpada — **wymaga zalogowania**. Zostaje przycisk.

### 9.1 Najpierw dwie rzeczy, które mogą oszczędzić resetu

1. ⚠ **Sprawdź naklejkę na obudowie.** Część nowszych MikroTików wychodzi z fabryki
   z **indywidualnym hasłem nadrukowanym na spodzie**, nie z pustym. Jeśli hasło
   „nie siadło", możliwe, że zmiana się nie zapisała i **stare nadal działa**.
2. ⚠ **Spróbuj Winboksa po adresie MAC**, nie po IP. Winbox znajduje router
   w zakładce **Neighbors** nawet wtedy, gdy adresacja jest do niczego — bo działa
   na warstwie drugiej. Wymaga **kabla wprost do portu LAN** routera.
   (⚠ 2026-08-28 `Ethernet 8` w laptopie był **Disconnected** — najpierw kabel.)

Reset kasuje **całą konfigurację**, więc warto te dwie minuty poświęcić.
W tym wypadku strata jest niewielka: plan z §1–§8 i tak zaczyna od zera,
a sieć `192.168.88.x` jest do usunięcia.

### 9.2 Reset przyciskiem — czas trzymania decyduje o trybie

**Kolejność: odłącz zasilanie → wciśnij i trzymaj `Reset` → podaj zasilanie,
nie puszczając przycisku → puść we właściwym momencie.**

Moment puszczenia wybiera tryb i **łatwo tu trafić nie tam, gdzie się chciało**:

| Puścisz gdy... | Co dostajesz |
|---|---|
| dioda **zaczyna migać** (ok. 5 s) | ✅ **reset konfiguracji** — to jest ten tryb |
| dioda **przestaje migać** (ok. 10 s) | ⛔ tryb **CAP** — router staje się punktem dostępowym sterowanym przez CAPsMAN i wygląda na zepsuty |
| trzymasz **dalej**, dioda gaśnie | tryb **Netinstall** — bootloader czeka na wgranie systemu po sieci |

⚠ **Czasy różnią się między modelami.** Sprawdź stronę swojego modelu na
`help.mikrotik.com`, jeśli dioda zachowuje się inaczej, niż w tabeli.

⛔ **Jeśli przycisk nie działa wcale** — najprawdopodobniej włączony jest
*protected bootloader*. Wtedy jedyną drogą jest **Netinstall** (kabel Ethernet
wprost do komputera, narzędzie Netinstall ze strony MikroTika, router w trybie
z ostatniego wiersza tabeli). Netinstall wgrywa system od nowa i **też kasuje hasło**.

### 9.3 Po resecie

- Logowanie: użytkownik **`admin`**, hasło **puste** (albo z naklejki — §9.1).
  RouterOS przy pierwszym wejściu **zażąda ustawienia nowego hasła**. Ustawić
  je od razu i zapisać w menedżerze haseł — to jest dokładnie ten krok, który
  „nie siadł".
- Router wraca na **`192.168.88.1`** z domyślną konfiguracją i DHCP na portach LAN.
  To jest stan wyjściowy dla §2 — **adres zmieniamy dopiero tam**, na `192.168.144.1`.
- ⚠ **Zachować konfigurację domyślną** (nie wybierać wariantu „bez domyślnej
  konfiguracji"). Domyślny zestaw ma gotowy most, DHCP i reguły firewalla,
  na których opiera się cały ten dokument — w szczególności `defconf: drop all
  not coming from LAN` z §5.1.
- Reset **nie zmienia wersji RouterOS** ani licencji. Wersję sprawdzić
  `/system/resource/print` — WireGuard i DHCP snooping wymagają **7.x**.

**Zaraz po ustawieniu hasła, zanim cokolwiek innego:**

```
/export file=przed-zmianami
```

Plik `przed-zmianami.rsc` zostaje na routerze (`/file/print`) i jest punktem,
do którego można wrócić, gdy kolejne kroki pójdą źle.

---

## 10. Czego ten dokument świadomie NIE robi

- **Nie odgradza drona od reszty sieci domowej.** W płaskiej sieci to niewykonalne
  na routerze — §1.2. Ochroną jest to, kogo się do tej sieci wpuszcza.
- **Nie wystawia niczego przez `dst-nat`.** Jedyne wejście z zewnątrz to tunel.
- **Nie daje dostępu do drona przez tunel.** Zdalny widz kończy na stacji — §5.2.
- **Nie daje dostępu z pola.** Za CGNAT na 4G to nie zadziała i tak było postanowione
  (decyzja 5) — w terenie ogląda się na monitorach stacji i w jej hotspocie.
- **Nie znosi ograniczenia „jeden klient MAVLink".** Laptop wpięty w `.12:19856`
  nadal odbiera telemetrię **kosztem kokpitu**. Droga bez konfliktu jest ta sama
  co dotąd: **rozgałęźnik w aparaturze**, `192.168.144.20:19856`
  ([WDROZENIE_RPI.md](WDROZENIE_RPI.md) §0).
- **Nie znosi ograniczenia kamery na `37256`** — jeden klient, a SIYI FPV trzyma go
  także w tle. Zasada przed lotem bez zmian: **zamknąć SIYI FPV**.
