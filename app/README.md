# 🚆 TreniRT — Treni italiani in tempo reale

App web minimalista per consultare lo stato dei treni italiani in tempo reale, usando le API non ufficiali di ViaggiaTreno.

## Funzionalità

- **Ricerca per stazione**: autocompletamento del nome, elenco partenze/arrivi con ritardo e binario
- **Destinazione opzionale**: filtra i treni mostrando solo quelli che arrivano davvero a una seconda stazione indicata, anche con un cambio treno (l'app cerca automaticamente una coincidenza a una fermata intermedia)
- **Inverti partenza/destinazione** con un tocco
- **Ricerche recenti**: le ultime 10 coppie partenza→destinazione e le ultime 10 ricerche per numero treno, salvate nel browser (localStorage) come scorciatoie
- **Scroll infinito**: caricamento automatico degli orari successivi arrivando in fondo alla lista
- **Ricerca per numero treno**: dettaglio completo con tutte le fermate, ritardo per ogni stazione, posizione del treno
- **Refresh manuale**: pulsante per aggiornare la lista treni o il dettaglio treno senza ridigitare la ricerca
- **Auto-refresh**: aggiornamento automatico ogni 60 secondi (sospeso mentre si guarda il dettaglio di un treno)
- **PWA installabile**: aggiungi alla home su Android per usarla come app

## Avvio rapido

```bash
pip install flask requests
python app.py
```

Poi apri `http://localhost:5000` nel browser.  
Dal telefono: `http://<IP-LOCALE>:5000`

## Installare come app su Android

1. Apri `http://<IP-LOCALE>:5000` in Chrome
2. Menu → "Aggiungi alla schermata Home"
3. L'app apparirà sulla home come una qualunque app nativa

## Note

- Le API di ViaggiaTreno non supportano CORS, perciò il server Flask funge da proxy
- L'app è uno **snapshot istantaneo** — non riceve push notifications
- I dati provengono da `http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/`

## API endpoints proxy

| Endpoint | Descrizione |
|----------|-------------|
| `/api/autocompleta/<q>` | Autocompletamento nome stazione |
| `/api/cercaStazione/<q>` | Cerca stazione (JSON) |
| `/api/partenze/<code>` | Partenze da una stazione |
| `/api/arrivi/<code>` | Arrivi a una stazione |
| `/api/cercaTreno/<num>` | Cerca treno per numero |
| `/api/andamento/<origin>/<num>` | Dettaglio andamento treno |

## Licenza

Distribuito sotto licenza [GNU AGPL-3.0-or-later](../LICENSE). In breve: chiunque distribuisca una versione modificata di questo software — anche solo facendola girare come servizio accessibile via rete, senza mai distribuire un binario — deve rendere disponibile il proprio codice sorgente modificato con la stessa licenza.

## Disclaimer

TreniRT è un progetto indipendente e amatoriale, **non affiliato, sponsorizzato o approvato da Trenitalia, RFI o Gruppo FS**. Utilizza gli stessi dati pubblici dell'infrastruttura ViaggiaTreno consultabili dal sito e dall'app ufficiali, ma non è un prodotto ufficiale né ne garantisce l'accuratezza. Il servizio è fornito "così com'è", senza alcuna garanzia — vedi la licenza per i termini completi.