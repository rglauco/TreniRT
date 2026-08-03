# Changelog

Tutte le modifiche rilevanti al progetto TreniRT (app Android).

## 2026-08-03 — Stazione reale per i treni "in stazione"

### Corretto
- **Etichetta "In stazione" fuorviante**: il flag `inStazione` di ViaggiaTreno indica solo che il treno è fermo da qualche parte lungo il suo percorso, non necessariamente alla stazione cercata. Ora, per i pochi treni con quel flag attivo, l'app recupera la stazione reale dell'ultimo rilevamento (la stessa già mostrata nel dettaglio treno) e mostra "🟢 Fermo a {stazione}" invece del generico "In stazione".

## 2026-08-02 — Ordine treni nella board con filtro destinazione

### Corretto
- **Treni fuori ordine cronologico nella lista con filtro destinazione**: `stopMatchedTrains` viene costruito concatenando più sottoinsiemi di match (già a destinazione, verificati tramite fermate, con cambio) — ciascuno ordinato internamente ma non nell'insieme una volta unito. La paginazione poi aggiungeva altri blocchi senza mai riordinare, peggiorando il disordine più a lungo restava aperta l'app. Ora si riordina per orario schedulato dopo ogni aggiornamento, come già avviene per la board principale.

## 2026-07-29 — Destinazione finale visibile solo senza filtro

### Corretto
- **Destinazione finale del treno mostrata solo quando non è impostato un filtro destinazione**: era stata tolta del tutto dalla card della lista perché ridondante col dettaglio treno, ma senza filtro destinazione era l'unica indicazione di direzione visibile in lista. Ora compare solo in assenza di filtro; con il filtro attivo la sostituisce il tragitto (partenza/arrivo/cambio) già mostrato.

## 2026-07-29 — Tragitto nel filtro destinazione

### Aggiunto
- **Il filtro per stazione di destinazione ora mostra il tragitto**, non solo l'orario di partenza dalla stazione cercata: quando partenza e destinazione sono entrambe indicate, la card del treno mostra sempre la destinazione finale, più due righe con orario in grassetto e nome stazione — partenza dalla stazione cercata e arrivo alla stazione di destinazione filtrata — collegate da una linea verticale in stile percorso.
- **Il cambio treno è integrato nella stessa visualizzazione**: quando serve una coincidenza, la timeline mostra un terzo punto intermedio con la stazione di cambio, l'orario di arrivo del treno visualizzato e di partenza di quello successivo, e nome/numero del treno di coincidenza — al posto della vecchia riga separata.

### Corretto
- **Coincidenze proposte che richiedevano di ripassare dalla stazione di partenza** (es. Coneglianoand → Sacile → di nuovo Coneglianoand → Preganziol, invece di un treno diretto): il calcolo del cambio ora scarta le coincidenze il cui treno di proseguimento dovrebbe riattraversare l'origine per raggiungere la destinazione.

## 2026-07-28 — Fix ricerche recenti per numero treno

### Corretto
- **Le ricerche recenti per numero treno smettevano di funzionare dal giorno dopo**: il tap su una voce salvata riusava lo stesso giorno di riferimento della ricerca originale, passato invariato all'API `andamentoTreno` — passato quel giorno, la richiesta non trovava più la corsa giusta. Ora selezionare una ricerca recente rilancia sempre una ricerca live del numero (come se lo si digitasse da capo) e mostra lo stato attuale del treno, indipendentemente da quando è stata salvata.

## 2026-07-25 — Fix "Non partito" nelle ricerche per arrivi

### Corretto
- **Treni già partiti da tempo mostrati come "Non partito"** nelle ricerche per arrivi (es. CONEGLIANO → PREGANZIOL, arrivi): l'app calcolava questa etichetta confrontando l'orario schedulato con l'orologio attuale, ma per una ricerca arrivi l'orario disponibile è quello di arrivo alla stazione cercata, non quello di partenza dall'origine — quindi qualunque treno ancora in viaggio, anche se partito da tempo, risultava "non partito" solo perché non ancora arrivato a destinazione. Ora si usa direttamente il campo `nonPartito` restituito dall'API di ViaggiaTreno, già calcolato correttamente lato server, sia nella lista treni che nel dettaglio treno.

## 2026-07-23 — Icona vettoriale definitiva (solo Android)

### Modificato
- **Icona convertita da PNG raster a vettore Android puro**: stesso identico disegno (locomotiva stilizzata nera su sfondo arancione), ma il layer in primo piano ora è un `VectorDrawable` invece di un'immagine raster — nitido a qualunque risoluzione/densità schermo, senza bisogno di più file PNG per densità diverse.
- Il disegno vettoriale definitivo arriva da un file SVG rifinito a mano (Inkscape) a partire dall'illustrazione IA originale, con canvas corretto per non tagliare muso/coda del treno.
- Rigenerate anche le icone legacy (`mipmap/ic_launcher.png`, usate come fallback su launcher più vecchi) dallo stesso disegno vettoriale.
- Scartata, dopo un test, una variante con sfumatura bianco-nero sulla locomotiva: a 48×48px (la dimensione reale minima dell'icona) la sfumatura appiattiva il disegno in una macchia grigia poco leggibile, soprattutto vicino al muso chiaro sullo sfondo arancione. Tenuta la versione a colore pieno nero.

## 2026-07-23 — Icona definitiva (solo Android)

### Modificato
- **Icona sostituita di nuovo**: dopo alcune iterazioni sul design vettoriale (locomotiva stilizzata, poi vista frontale), scelta un'illustrazione di un treno ad alta velocità generata dall'utente con IA — su sfondo arancione acceso, invariato dalle iterazioni precedenti.
- L'asset è una **PNG raster** (non più interamente vettoriale come i tentativi precedenti): il layer di sfondo resta un vettore Android (arancione pieno), il layer in primo piano è l'illustrazione con sfondo trasparente.
- **Nota tecnica emersa durante il test**: il sistema delle icone adattive di Android ritaglia automaticamente il layer "foreground" a circa il 66% dell'area del canvas *prima* di applicare la sagoma del launcher (cerchio/squircle) — un comportamento non replicato correttamente da un primo test fatto solo con una maschera circolare "a mano", che aveva mostrato un risultato ingannevolmente positivo. Il contenuto è stato ridimensionato per rispettare questa zona sicura reale, verificato installando l'app e ispezionando l'icona vera sia nella hotseat che nel cassetto app dell'emulatore.

## 2026-07-23 — Nuova icona (solo Android)

### Modificato
- **Icona dell'app ridisegnata**: la vecchia icona (una forma astratta poco leggibile) è stata sostituita con il profilo netto di un treno ad alta velocità visto di lato, muso aerodinamico a punta, due finestrini, e linee di velocità gialle che suggeriscono il movimento — su sfondo rosso acceso, per risaltare bene tra le altre app in home screen. Icona interamente vettoriale (nessuna immagine esterna), verificata leggibile anche alla dimensione minima reale (48×48px) e con mascheramento circolare del launcher.

## 2026-07-23 — Licenza e disclaimer

### Aggiunto
- **Licenza GNU AGPL-3.0-or-later** per l'intero progetto (file `LICENSE` alla radice, testo ufficiale invariato). Scelta per tutelare il progetto da chi volesse prendere il codice, chiuderlo e farlo girare come servizio commerciale senza ridare nulla indietro — l'AGPL copre anche l'uso "solo come servizio web", a differenza della GPL normale.
- Intestazioni `SPDX-License-Identifier: AGPL-3.0-or-later` nei file sorgente principali.
- **README principale del progetto** (prima assente), con sezioni Licenza e Disclaimer.
- **Disclaimer "Chi siamo"** aggiunto in fondo alla pagina di aiuto in-app: TreniRT non è affiliato, sponsorizzato o approvato da Trenitalia/RFI/Gruppo FS, ed è fornito così com'è senza garanzie.

### Nota
Valutati anche i passaggi per una futura pubblicazione su F-Droid (dipendenze e permessi già compatibili — nessuna libreria proprietaria, nessun tracker; mancano ancora un repository pubblico e la scelta definitiva del percorso di pubblicazione).

## 2026-07-23 (solo Android)

### Aggiunto
- **Tema chiaro**, oltre a quello scuro esistente — utile per chi usa l'app sotto il sole, dove uno schermo nero è difficile da leggere. Pulsante ☀️/🌙 in alto per passare dall'uno all'altro; la scelta resta salvata tra un avvio e l'altro.
- **Pagina di aiuto in italiano** (pulsante ❓ in alto): spiega come funziona la ricerca per stazione e per numero treno, la cronologia, l'aggiornamento automatico/manuale, e in particolare il limite della ricerca di treni nel passato quando non c'è una cache di sessione (perché a volte compare "orario nel passato — orari di domani" invece dei treni già passati).

### Corretto
- **Logo in alto coperto dall'orologio/icone di sistema, e ultima card della lista coperta dai tasti di navigazione Android**: l'app usa la modalità edge-to-edge ma non teneva conto delle barre di sistema nel proprio padding. Aggiunto il padding necessario in base agli inset reali del dispositivo, così il contenuto non finisce più sotto la barra di stato in alto né sotto la barra di navigazione in basso.

## 2026-07-22 (solo Android)

### Corretto
- **Tasto indietro hardware/gesture usciva sempre dall'app**, anche quando si stava guardando il dettaglio di un treno (con le fermate) — invece di tornare alla lista precedente come fa il pulsante "← Indietro" in app. Ora il tasto indietro di sistema si comporta come quel pulsante quando si è nel dettaglio treno.
- **I treni già partiti sparivano dalla lista partenze/arrivi** se si richiedeva di nuovo la stessa stazione con un orario precedente. Causa: l'endpoint di ViaggiaTreno per partenze/arrivi è una "lavagna live" ancorata all'orologio reale del server — non conserva alcuno storico, e oltre un certo margine (circa 2 ore) risponde semplicemente vuoto qualunque orario passato si richieda; verificato inviando richieste dirette all'API con vari orari. Ora l'app accumula i risultati per la sessione corrente (stazione + filtro + giorno) invece di sostituire la lista ad ogni richiesta, così i treni già visti restano visibili — e cliccabili per vederne la posizione — anche dopo che l'API smette di restituirli. Le voci più vecchie di 3 ore vengono scartate per non far crescere la lista all'infinito.
- **"In orario" mostrato per treni non ancora partiti**: l'etichetta si basava solo sul campo ritardo, che vale 0 sia per "confermato in orario" sia per "nessuna informazione perché non è ancora partito". Ora si confronta l'orario schedulato con l'ora reale attuale: se il treno non ha ancora raggiunto il proprio orario, viene mostrato "Non partito" invece di "In orario".
- **Spinner di caricamento bloccato in loop infinito**: lo scroll infinito si autoattivava senza sosta quando la lista visibile era molto corta (es. un solo treno, come nel fallback "dati non ancora disponibili") — la condizione "vicino al fondo della lista" era sempre vera con pochi elementi, quindi il caricamento successivo scattava di continuo. Aggiunto un flag che ferma i tentativi automatici non appena una richiesta non trova più treni nuovi da aggiungere.

### Nota — limite noto: ricerca per stazione vs. ricerca per numero treno
Cercando un treno per **numero** (`andamentoTreno`), ViaggiaTreno restituisce il suo record completo con tutte le fermate per l'intera giornata, consultabile in qualsiasi momento dopo — che sia partito 5 minuti o 8 ore fa. Non è una finestra scorrevole: è uno storico persistente legato a quel singolo treno.

La ricerca per **stazione** (`partenze`/`arrivi`) è invece una vera lavagna live, ancorata all'orologio reale del server: mostra solo una finestra scorrevole di treni intorno all'istante attuale e, oltre un certo margine (circa 2 ore), risponde vuota qualunque orario passato si richieda (verificato con richieste dirette all'API). **Non esiste alcun endpoint di ViaggiaTreno che permetta di consultare lo storico delle partenze/arrivi di una stazione** — quel dato, una volta uscito dalla finestra live, non è recuperabile in alcun modo, nemmeno incrociando altre chiamate.

Per questo l'accumulo in sessione (fix sopra) aiuta solo se l'app aveva **già visto** quei treni con una richiesta live precedente nella stessa sessione (es. con "Adesso"): in tal caso li tiene in memoria invece di farli sparire. Se invece si chiede direttamente un orario passato senza che l'app abbia mai interrogato quella fascia oraria mentre era ancora "viva" (es. subito dopo l'apertura dell'app), non c'è nulla da recuperare, e l'app mostra correttamente il fallback "orario nel passato — orari di domani". È un limite strutturale della fonte dati, non risolvibile lato client.

## 2026-07-21

### Corretto
- **Crash all'avvio della ricerca** (stazione o numero treno): la Compose BOM `2024.01.00` fissava `material3` alla versione `1.1.2` mentre le altre librerie Compose andavano alla `1.6.0` — incompatibilità binaria tra le due. Aggiornata la BOM a `2024.02.00`, dove le versioni sono di nuovo allineate.
- **"Nessun treno trovato" fuorviante**: gli errori di rete venivano ingoiati silenziosamente e mostrati come "nessun treno" invece di un errore reale. Ora la UI distingue i due casi.
- **Partenze/arrivi sempre falliti**: `java.net.URLEncoder` codifica gli spazi come `+`, valido solo nelle query string — ma il valore veniva inserito in un segmento di path URL, dove il server non lo reinterpreta come spazio. L'orario passato all'API arrivava quindi corrotto, causando sempre un errore dal server. Corretta la codifica a `%20`.
- **Filtro destinazione impreciso**: contava come "raggiungibile" anche una fermata del treno avvenuta *prima* della stazione di partenza nel suo percorso (es. un treno che passa da una città, poi arriva alla tua stazione, poi prosegue altrove) — ora l'ordine delle fermate viene verificato correttamente rispetto alla direzione di marcia.
- **Dati del giorno sbagliato**: le richieste di dettaglio treno (`andamentoTreno`) usavano sempre la mezzanotte di "oggi" come riferimento, anche quando si stava consultando l'orario di "domani" (fallback per orari già passati). Se lo stesso numero treno circolava anche oggi, l'app mostrava dati di oggi spacciati per quelli di domani. Ora si usa il giorno di riferimento specifico che l'API fornisce per ciascun treno (`dataPartenzaTreno`).
- **Messaggio "nessun treno" quando in realtà i dati non erano ancora disponibili**: ViaggiaTreno non ha dati "live" per corse future non ancora attive nel sistema (tipicamente quelle di domani, prima che inizi la giornata). In questo caso l'app ora mostra un avviso chiaro e l'elenco completo non verificato, invece di affermare erroneamente che non ci sono soluzioni.

### Aggiunto
- **Filtro per stazione di destinazione** (opzionale): oltre a cercare per stazione di partenza, si può indicare anche una destinazione — l'elenco mostra solo i treni che effettivamente ci arrivano.
- **Ricerca con un cambio treno**: se nessun treno diretto raggiunge la destinazione, l'app cerca automaticamente una coincidenza a una fermata intermedia, mostrando stazione di cambio, orari e treno di proseguimento.
- **Pulsante "Inverti partenza/destinazione"**: scambia le due stazioni in un tocco.
- **Cronologia ricerche**: le ultime 10 coppie partenza→destinazione e le ultime 10 ricerche per numero treno vengono salvate e proposte come scorciatoie rapide.
- **Time picker a rotellina** al posto della digitazione manuale dell'orario.
- **Refresh manuale**: pulsante per aggiornare la lista treni per stazione e il dettaglio treno (con fermate) senza dover ridigitare la ricerca.
- **Scroll infinito**: arrivando in fondo alla lista treni, vengono caricati automaticamente gli orari successivi.
- **Numero di versione visibile in app**, per sapere sempre quale build si sta usando (Android: v1.3.0 nella barra superiore).
- **Pulsante "X"** per cancellare rapidamente il campo stazione di partenza (già presente per la destinazione).
- Repository GitHub (privato) con `.gitignore` per Android/Kotlin/Gradle.

## Versioni Android
- v1.0 → v1.1.0: filtro destinazione, time picker, cronologia ricerche, refresh, fix vari.
- v1.1.0 → v1.2.0: ricerca con cambio treno, scroll infinito.
- v1.2.0 → v1.3.0: fix del bug sul giorno di riferimento, messaggio di verifica-non-disponibile.
- v1.3.0 → v1.4.0: tasto indietro corretto, treni già partiti non spariscono più dalla lista.
- v1.4.0 → v1.4.1: fix etichetta "In orario" per treni non ancora partiti, fix loop infinito dello scroll.
- v1.4.1 → v1.5.0: tema chiaro, pagina di aiuto, fix padding barre di sistema (logo e ultima card non più coperti).
- v1.5.0 → v1.5.1: nuova icona (treno ad alta velocità su sfondo rosso acceso).
- v1.5.1 → v1.5.2: icona sostituita di nuovo — illustrazione IA del treno su sfondo arancione, fix zona sicura icona adattiva.
- v1.5.2 → v1.5.3: icona convertita da PNG raster a vettore Android puro (stesso disegno), nitida a ogni densità schermo.
- v1.5.3 → v1.5.4: fix "Non partito" mostrato erroneamente per treni già partiti nelle ricerche per arrivi.
- v1.5.4 → v1.5.5: fix ricerche recenti per numero treno che smettevano di funzionare il giorno dopo.
- v1.5.5 → v1.5.6: tragitto (partenza/arrivo/cambio) nel filtro destinazione, fix coincidenze che ripassavano dall'origine.
- v1.5.6 → v1.5.7: destinazione finale del treno mostrata solo quando non c'è un filtro destinazione attivo.
- v1.5.7 → v1.5.8: fix ordine cronologico dei treni nella board con filtro destinazione.
- v1.5.8 → v1.5.9: stazione reale mostrata per i treni con etichetta "in stazione".
