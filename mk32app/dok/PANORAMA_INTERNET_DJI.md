# Panorama — odbiór DJI przez internet

Decyzja użytkownika 2026-09-05: umożliwić klientom DJI nadawanie obrazu
do Panoramy przez internet z naszego APK zrzutu ekranu (Horyzont).
To rozszerza wcześniejszy plan dostępu wyłącznie
przez WireGuard o wejście dla nadawców obrazu.

**Wdrożone na MikroTiku i stacji. Przekierowanie TP-Linka zapisane przez
użytkownika i potwierdzone w panelu. Dostęp przez internet wymaga jeszcze
rozwiązania NAT przed TP-Linkiem i testu z sieci zewnętrznej.**

## Stan potwierdzony przez SSH 2026-09-05

- MikroTik hEX lite, RouterOS 7.24.1.
- WAN: ether1 w liście WAN, adres z DHCP `192.168.1.117/24`.
- Brama MikroTika: `192.168.1.2` — drugi router, wymagany kolejny dst-nat.
- Stacja: `192.168.88.30`, brama `192.168.88.1`.
- Usługi `panorama-gcs` i `panorama-mediamtx`: active.
- TCP 1935 (RTMP) oraz TCP 5601 (APK zrzutu ekranu): nasłuchują.
- Przed zmianą: brak dst-nat i końcowy drop w łańcuchu forward.

## Porty według sposobu nadawania

| Sposób | Port docelowy stacji | Zastosowanie |
|---|---|---|
| RTMP z DJI | TCP 1935 | Sam obraz z aparatury; każdy dron ma ścieżkę i hasło źródła |
| Nasz APK zrzutu ekranu | TCP 5601 | Osobny odbiornik surowego H.264; aktualny kod obsługuje jednego nadawcę naraz |
| DJI Cloud API | TCP 1883 + strona konfiguracji | Osobny zakres: MQTT z telemetrią i konfiguracja platformy, niepotrzebne do samego RTMP |

Porty panelu i odtwarzania 8095/TCP, 8889/TCP, 8189/UDP nie są potrzebne
do nadawania przez APK. API MediaMTX 9997 pozostaje na loopback.
RTMP 1935 jest używany między odbiornikiem APK a MediaMTX lokalnie na stacji;
nie potrzebuje przekierowania na routerze. MQTT 1883 też nie jest potrzebny.

## Przekierowanie APK

Wdrożony skrypt: `../serwer/rpi/mikrotik-panorama-apk.rsc`.
Dodaje dst-nat TCP 5601 z WAN na `192.168.88.30:5601` i wąską regułę
forward przed końcowym drop. Nie restartuje routera ani stacji.
Z Windows uruchamia go `../serwer/rpi/wgraj-mikrotik-apk.ps1`.
Wrapper wysyła blok jako jedno polecenie (RouterOS rozdziela nowe linie
w sesji SSH) i wymaga znacznika zakończenia, ponieważ sam kod wyjścia SSH
nie potwierdza poprawnego wykonania. Powtórne wykonanie sprawdzono:
pozostaje jedna reguła dst-nat i jedna reguła forward dla APK.

Na routerze nadrzędnym `192.168.1.2` użytkownik zapisał przekierowanie,
potwierdzone w panelu Virtual Servers (wpis Panorama, Enabled, WAN1 i WAN2,
protokół ALL obejmujący wymagany TCP):

```text
TCP 5601 z internetu -> 192.168.1.117 TCP 5601
```

Adres `192.168.1.117` należy utrwalić rezerwacją DHCP na routerze nadrzędnym.
Żaden z WAN-ów TP-Linka nie ma bezpośrednio publicznego IPv4.
Dokładny stan obu połączeń opisano na końcu dokumentu.

W panelu ADMIN Panoramy utwórz źródło nadawane DJI i pobierz hasło tego źródła.
W APK wpisz adres i osobno hasło źródła:

```text
Adres stacji: PUBLICZNY_HOST:5601
Hasło: HASLO_ZRODLA
```

Protokół APK w tej konfiguracji nie szyfruje obrazu ani hasła w transmisji.
Nie umieszczać rzeczywistych adresów z hasłami w repozytorium ani raportach.

## Weryfikacja i cofnięcie

Końcowy test wymaga klienta spoza LAN (np. aparatura przez LTE): nadawanie
właściwym hasłem, obraz w Panoramie i odmowa dla błędnego hasła.
Samo otwarte gniazdo TCP nie potwierdza odbioru obrazu. Próba przez publiczny
adres z LAN może wymagać hairpin NAT, więc nie zastępuje próby z internetu.

Liczniki reguł na MikroTiku:

```routeros
/ip/firewall/nat/print stats where comment="PANORAMA: DJI APK WAN -> GSB"
/ip/firewall/filter/print stats where comment="PANORAMA: allow DJI APK to GSB"
```

Wyłączenie wyłącznie tych dwóch reguł:

```routeros
/ip/firewall/nat/disable [find where comment="PANORAMA: DJI APK WAN -> GSB"]
/ip/firewall/filter/disable [find where comment="PANORAMA: allow DJI APK to GSB"]
```

Wyłączenie blokuje nowe połączenia; istniejące mogą trwać dzięki conntrack
i regułom established/related. Nadawanie należy zakończyć na aparaturze.
Skrypt instalacyjny nie włącza ponownie reguł wyłączonych ręcznie.

## Poprawka odbiornika przed wystawieniem portu

`server/zrzut.mjs`: odrzucanie nieprawidłowych typów JSON bez wyjątku,
limit 512 bajtów nagłówka także z końcowym znakiem nowej linii,
5 sekund na uwierzytelnienie i najwyżej 16 oczekujących gniazd.
Zamknięcie obcego lub starego gniazda nie kończy bieżącego nadawania.
Ponowne sprawdzenie zajętości po uwierzytelnieniu zapobiega przejęciu
strumienia przez drugiego oczekującego klienta.

Testy regresji: `node --test tests/zrzut.test.mjs` (8 przypadków).
Nie zmieniono formatu protokołu APK ani zasady jednego nadawcy naraz.

## Wynik wdrożenia 2026-09-05

- Poprawka wgrana na `/opt/panorama/server/zrzut.mjs`; SHA-256:
  `5db7c52942d4044483080e68363cf44c71de28914b5e1feae203a310662a325f`.
- Kopia poprzedniej wersji na stacji:
  `/var/lib/panorama/backup-apk-wan.WpDna4/zrzut.mjs`.
- Zrestartowano wyłącznie `panorama-gcs`. Obie usługi Panoramy aktywne.
- Sprawdzono połączenie z `192.168.1.50` do WAN MikroTika
  `192.168.1.117:5601`, przechodzące przez dst-nat do odbiornika na stacji.
- Nagłówek `null` oraz nieprawidłowe hasło odrzucone zamknięciem połączenia;
  PID serwera pozostał ten sam, `NRestarts=0`.
- Liczniki docelowych reguł NAT i forward potwierdziły przejście ruchu.
- Nie testowano jeszcze obrazu z APK przez internet.

## Weryfikacja TP-Linka po zmianie użytkownika 2026-09-05

Panel `192.168.1.2`: TP-Link TL-ER5120 v4.0, firmware
4.0.2 Build 20200313 Rel.52726. Odczyt bez zmieniania ustawień routera.

W Virtual Servers wpis `Panorama`: Enabled, WAN1 i WAN2,
zewnętrzny port 5601, wewnętrzny port 5601, serwer `192.168.1.117`,
protokół ALL. Wpis obejmuje wymagany TCP; APK nie potrzebuje UDP.

| Łącze | Adres WAN TP-Linka | Brama | Stan |
|---|---|---|---|
| WAN1 | 192.168.100.98/24, statyczny | 192.168.100.1 | Link Up; adres prywatny, kolejny router przed TP-Linkiem |
| WAN2 | 100.121.119.2/10, dynamiczny | 100.64.0.1 | Link Up; zakres współdzielony dla CGNAT |

Na WAN1 należy ustalić model i WAN urządzenia `192.168.100.1` oraz ewentualne
przekierowanie TCP 5601 na `192.168.100.98:5601`. Nie sprawdzono jeszcze,
czy urządzenie to ma publiczny adres ani czy przekierowanie już istnieje.
WAN2 używa przestrzeni 100.64.0.0/10, która nie jest globalnie routowalna:
[RFC 6598](https://www.rfc-editor.org/rfc/rfc6598).
Reguła na samym TP-Linku nie tworzy mapowania w NAT operatora.

Źródło reguł: [MikroTik — port forwarding](https://help.mikrotik.com/docs/spaces/RKB/pages/154042388/Port%20forwarding).
