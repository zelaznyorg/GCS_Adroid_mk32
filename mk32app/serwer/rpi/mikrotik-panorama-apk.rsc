# Panorama: APK zrzutu ekranu DJI, WAN -> 192.168.88.30:5601/TCP.
# Dla sprawdzonej konfiguracji hEX z 2026-09-05 (WAN=ether1).
# Powtorne wykonanie nie dodaje duplikatow. Nie otwiera panelu WWW.
# Importuj jako plik .rsc; przez SSH uzyj wgraj-mikrotik-apk.ps1.
{
    :local natTag "PANORAMA: DJI APK WAN -> GSB";
    :local filterTag "PANORAMA: allow DJI APK to GSB";
    :local dropRule [/ip/firewall/filter/find where chain=forward and action=drop and comment="DRON15: reszta zablokowana"];
    :if ([:len $dropRule] != 1) do={ :error "Nie znaleziono jednoznacznej koncowej reguly drop forward"; };
    :if ([:len [/interface/list/member/find where list=WAN and interface=ether1]] != 1) do={ :error "WAN nie odpowiada sprawdzonej konfiguracji ether1"; };
    :if ([:len [/ip/firewall/nat/find where comment=$natTag]] = 0) do={
        /ip/firewall/nat/add chain=dstnat action=dst-nat in-interface-list=WAN dst-address-type=local protocol=tcp dst-port=5601 to-addresses=192.168.88.30 to-ports=5601 comment=$natTag;
    };
    :if ([:len [/ip/firewall/filter/find where comment=$filterTag]] = 0) do={
        /ip/firewall/filter/add chain=forward action=accept connection-state=new connection-nat-state=dstnat in-interface-list=WAN out-interface=bridge1 protocol=tcp dst-address=192.168.88.30 dst-port=5601 place-before=$dropRule comment=$filterTag;
    };
    /ip/firewall/nat/print detail where comment=$natTag;
    /ip/firewall/filter/print detail where comment=$filterTag;
    :put "PANORAMA_APK_RULES_OK";
}
