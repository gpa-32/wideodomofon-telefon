# Grupa dzwonienia z podglądem na wszystkich urządzeniach – wymagania dla serwera (Asterisk)

Dokument dla projektu serwera Domofon SIP. Opisuje, co serwer musi robić, żeby wszystkie urządzenia
(tablety, telefony) widziały obraz z bramofonu **podczas dzwonienia**, dzwoniły do pierwszego
prawdziwego odebrania, a po odebraniu przez jedno urządzenie pozostałe się rozłączały.

## Dlaczego nie da się tego zrobić samą aplikacją

Asterisk nie przekazuje obrazu od dzwoniącego do dzwonionych urządzeń przed odebraniem. Aplikacja
obchodzi to „cichym odebraniem": odbiera połączenie z wyciszonym mikrofonem i głośnikiem, żeby obraz
zaczął płynąć. Przy zwykłym `Dial()` do grupy pierwsze takie odebranie kończy dzwonienie na
pozostałych urządzeniach. Żeby wszystkie mogły odebrać po cichu jednocześnie, połączenie z bramofonem
musi trafić do konferencji, a nie do `Dial()`.

## Zachowanie aplikacji (już zaimplementowane, v1.1.10+)

- Opcja „Obraz podczas dzwonienia (ciche odebranie)": aplikacja odbiera połączenie ok. 0,5 s po jego
  nadejściu, mikrofon i głośnik wyciszone, lokalnie nadal gra dzwonek i widać przyciski
  Odbierz / Otwórz furtkę / Odrzuć. W sieci bramofonu (kamera RTSP osiągalna) cichego odebrania nie ma.
- **Odbierz** – aplikacja włącza dźwięk i, jeśli ustawiono „Kod odebrania dla serwera" (`answer_dtmf`),
  wysyła ten kod DTMF. To jest sygnał dla serwera „to urządzenie przejmuje rozmowę".
  Urządzenie bez podglądu wysyła ten sam kod ok. 0,7 s po zwykłym odebraniu.
- **Odrzuć** – aplikacja kończy swoje połączenie (BYE). Pozostałe urządzenia mają dzwonić dalej.
- **Otwórz furtkę** – aplikacja wysyła kod DTMF furtki (domyślnie `#`), który musi dotrzeć do bramofonu,
  także od urządzenia, które jeszcze „nie odebrało".
- Gdy serwer zakończy połączenie urządzenia (BYE), ekran rozmowy znika.
- DTMF: aplikacja wysyła RFC2833 i/lub SIP INFO zależnie od ustawienia „Sposób wysyłania DTMF".
  Przy tej funkcji należy używać **jednego** sposobu (zalecane RFC2833), żeby serwer nie odebrał kodu dwa razy.

## Wymagane zachowanie serwera

1. Połączenie przychodzące z bramofonu do grupy: serwer odbiera je (`Answer`) i umieszcza w konferencji
   (`ConfBridge`) utworzonej dla tego połączenia. Bramofon jest źródłem obrazu dla wszystkich
   (`video_mode = first_marked`, bramofon jako `marked`), konferencja kończy się, gdy bramofon się rozłączy
   (`end_marked = yes`).
2. Serwer dzwoni równolegle do wszystkich urządzeń grupy (`Originate` na każde konto). Urządzenie, które
   odbierze (po cichu lub normalnie), dołącza do konferencji **wyciszone** (`startmuted = yes`) i nie słyszy
   ani nie jest słyszane do czasu przejęcia rozmowy. Obraz z bramofonu ma dostawać od razu.
3. Kod DTMF furtki (np. `#`) od dowolnego uczestnika musi być przekazany do bramofonu
   (`dtmf_passthrough = yes`) – otwarcie furtki bez odbierania ma działać.
4. Kod odebrania (np. `*1`, ten sam co `answer_dtmf` w aplikacji) od uczestnika:
   - wyłącza wyciszenie tego uczestnika (rozmowa bramofon ↔ to urządzenie),
   - rozłącza wszystkich pozostałych uczestników oprócz bramofonu,
   - anuluje dzwonienie do urządzeń, które jeszcze nie odebrały.
5. Urządzenie, które się rozłączy przed przejęciem (Odrzuć), po prostu opuszcza konferencję; reszta zostaje.
6. Brak przejęcia przez czas dzwonienia (np. 60 s) albo rozłączenie bramofonu: serwer kończy wszystkie
   połączenia tej konferencji.
7. Obraz: H.264 bez transkodowania; serwer musi pośredniczyć w mediach (urządzenia są za NAT-em),
   `rtp_symmetric`, `force_rport`, `rewrite_contact` na kontach urządzeń, `direct_media = no`.

## Testy akceptacyjne

- Dwa urządzenia z podglądem + jedno bez: po naciśnięciu przycisku na bramofonie wszystkie trzy dzwonią,
  dwa pierwsze od razu pokazują obraz.
- Odebranie na jednym: rozmowa z dźwiękiem w obie strony, pozostałe dwa przestają dzwonić w ciągu 2 s.
- Odrzucenie na jednym: pozostałe dwa dzwonią dalej.
- „Otwórz furtkę" na urządzeniu, które nie odebrało: przekaźnik bramofonu zadziałał.
- Nikt nie odbiera: po czasie dzwonienia wszystkie ekrany znikają, bramofon wraca do stanu spoczynku.

## Zalecenie: kod odebrania w nagłówku połączenia (aplikacja v1.1.12+)

Zamiast polegać na ustawieniu w każdym urządzeniu, serwer może dodać do INVITE wysyłanego do urządzeń
w trybie konferencji nagłówek:

    X-Domofon-Answer: *1

Aplikacja użyje wtedy tego kodu po „Odbierz" dla tego jednego połączenia, niezależnie od swoich ustawień.
W zwykłym połączeniu (bez konferencji) nagłówka ma nie być – aplikacja nie wyśle wtedy kodu z nagłówka,
więc nie ma ryzyka, że kod odebrania trafi do bramofonu i otworzy furtkę.
W Asterisku (PJSIP): `Set(PJSIP_HEADER(add,X-Domofon-Answer)=*1)` w pre-dial handlerze kanału urządzenia.
