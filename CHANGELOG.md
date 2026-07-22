# Changelog

Tutte le modifiche rilevanti al progetto TreniRT (app Android + web/PWA).

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
- **Time picker a rotellina** (solo Android; il web usa il selettore orario nativo del browser) al posto della digitazione manuale dell'orario.
- **Refresh manuale**: pulsante per aggiornare la lista treni per stazione e il dettaglio treno (con fermate) senza dover ridigitare la ricerca.
- **Scroll infinito**: arrivando in fondo alla lista treni, vengono caricati automaticamente gli orari successivi.
- **Numero di versione visibile in app**, per sapere sempre quale build si sta usando (Android: v1.3.0 nella barra superiore).
- **Pulsante "X"** per cancellare rapidamente il campo stazione di partenza (già presente per la destinazione).
- Repository GitHub (privato) con `.gitignore` per Python e Android/Kotlin/Gradle.

## Versioni Android
- v1.0 → v1.1.0: filtro destinazione, time picker, cronologia ricerche, refresh, fix vari.
- v1.1.0 → v1.2.0: ricerca con cambio treno, scroll infinito.
- v1.2.0 → v1.3.0: fix del bug sul giorno di riferimento, messaggio di verifica-non-disponibile.
- v1.3.0 → v1.4.0: tasto indietro corretto, treni già partiti non spariscono più dalla lista.
- v1.4.0 → v1.4.1: fix etichetta "In orario" per treni non ancora partiti, fix loop infinito dello scroll.
