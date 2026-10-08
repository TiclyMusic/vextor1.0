# Vextor 1.0

Un modello AI personale che crea **siti web (HTML, CSS, JS) con UI pulita**, addestrato su
migliaia di repository GitHub, e un'**app Android** per usarlo offline sul telefono.

```
GitHub (1000+ repo di siti)  ──►  dataset (richiesta → sito)  ──►  LoRA su Qwen2.5-Coder  ──►  GGUF  ──►  app Android (llama.cpp)
   scraper/scrape_repos.py          scraper/build_dataset.py        training/ (Colab GPU)                   android/
```

## Struttura

| Cartella | Cosa fa |
|---|---|
| `scraper/scrape_repos.py` | Cerca su GitHub repo di landing page, portfolio, template, dashboard… (solo licenze permissive: MIT, Apache, BSD…) e scarica solo i file HTML/CSS/JS. Riprendibile. |
| `scraper/build_dataset.py` | Unisce CSS/JS in un unico file HTML per pagina, sostituisce le immagini con placeholder, rimuove analytics/email, filtra per qualità della UI (responsive, flex/grid, tag semantici…), deduplica e genera la richiesta in linguaggio naturale (IT/EN) che descrive ogni sito. |
| `training/train_lora.py` | Fine-tuning LoRA di `Qwen2.5-Coder-1.5B-Instruct` (o 3B) sul dataset; `--merge` unisce la LoRA. |
| `training/export_gguf.sh` | Converte il modello in GGUF (q8_0 o q4_k_m) per llama.cpp. |
| `training/Vextor_Colab.ipynb` | Notebook Colab che fa tutto: dataset → training → prova → export → upload. |
| `android/` | App chat Android (Kotlin + Compose) con inferenza on-device via llama.cpp, anteprima del sito in WebView, salvataggio/condivisione dell'HTML. |
| `.github/workflows/android.yml` | Compila l'APK su GitHub a ogni modifica di `android/` e lo pubblica nella release **apk-latest**. |
| `.github/workflows/dataset.yml` | Esegue lo scraping su GitHub Actions e pubblica il dataset nella release **dataset-latest**. |

## 1. Provare subito l'app sul Galaxy S25

1. Dal telefono apri **Releases → "Vextor APK (ultima build)"** del repo e scarica `vextor.apk`
   (oppure *Actions → Android APK → ultimo run → Artifacts*).
2. Installa l'APK (Android chiederà di consentire l'installazione da questa fonte).
3. Nell'app tocca l'icona **chip** (Modelli) → *Catalogo* → **Qwen2.5 Coder 1.5B** (1.1 GB) o **3B** (2 GB).
   Il modello base sa già scrivere HTML: puoi provare la chat mentre il tuo modello si addestra.
4. Scrivi ad esempio *"Crea una landing page per un'app di meditazione, tema scuro, palette viola"*.
   Quando il codice è pronto tocca **Anteprima** per vedere il sito, poi salvalo in `Download/Vextor` o condividilo.

Il Galaxy S25 (Snapdragon 8 Elite, 12 GB RAM) gestisce senza problemi i modelli 1.5B e 3B:
llama.cpp sceglie a runtime le istruzioni migliori della CPU (dotprod, i8mm…).
In *Impostazioni* puoi regolare temperatura, lunghezza risposta, contesto e thread.

## 2. Creare il dataset (1000+ repo)

**Su GitHub (consigliato):** il workflow *Dataset (scraping GitHub)* parte da solo quando cambia
`scraper/`, oppure manualmente da *Actions → Dataset → Run workflow* (dal branch principale).
Per andare più veloce aggiungi un secret `SCRAPER_TOKEN` (Personal Access Token, anche senza permessi):
il token di default delle Actions è limitato a 1000 richieste/ora.
Il risultato finisce nella release **dataset-latest** (`dataset.tar.gz`).

**In locale:**

```bash
pip install -r scraper/requirements.txt
export GITHUB_TOKEN=ghp_...                      # https://github.com/settings/tokens
python scraper/scrape_repos.py --max-repos 1200  # -> data/raw/
python scraper/build_dataset.py                  # -> data/dataset/train.jsonl, val.jsonl
```

Opzioni utili di `build_dataset.py`: `--pages-per-repo 2`, `--max-chars 24000`, `--min-score 5`,
`--variants 2` (più richieste diverse per lo stesso sito).
Ogni esempio è una conversazione `system → user (richiesta) → assistant (```html …```)`, con
sorgente e licenza del repo originale.

## 3. Addestrare il modello

Serve una GPU: apri `training/Vextor_Colab.ipynb` in Google Colab
(*File → Apri notebook → GitHub*), seleziona runtime GPU ed esegui le celle.
Se il codice non è ancora sul branch `main`, cambia `BRANCH` nella prima cella.

In locale con GPU NVIDIA:

```bash
pip install -r training/requirements.txt
python training/train_lora.py --data data/dataset --out out/vextor-lora --max-len 6144 --epochs 1
python training/train_lora.py --merge --out out/vextor-lora          # -> out/vextor-merged
bash training/export_gguf.sh out/vextor-merged out/vextor-q8_0.gguf q8_0
```

| GPU | Modello | `--max-len` | Tempo indicativo (1 epoca, ~1500 esempi) |
|---|---|---|---|
| T4 16 GB (Colab gratis) | 1.5B | 6144 | 1–2 h |
| L4 / A100 | 1.5B o 3B | 8192 | 30–90 min |

## 4. Usare il tuo modello nell'app

- Carica il `.gguf` su Hugging Face (il notebook lo fa per te) e incolla il link in
  *Modelli → Scarica dal link*, **oppure**
- copia il file sul telefono e usa *Modelli → Scegli file .gguf*.

Il system prompt dell'app è lo stesso usato nel training, così il modello risponde sempre con un
unico file HTML completo.

## Compilare l'APK in locale

Serve Android SDK con NDK `29.0.13113456` e CMake `3.31.6`:

```bash
cd android
./gradlew assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
# opzionale, per non riscaricare llama.cpp: -PllamaSourceDir=/percorso/llama.cpp (tag b11400)
```

L'APK è firmato con una chiave di test inclusa nel repo (`android/app/vextor-test.keystore`),
così ogni nuova build si installa come aggiornamento. Non usarla per pubblicare sul Play Store.

## Note

- Lo scraper scarica solo repo con licenza permissiva; ogni esempio del dataset conserva
  l'URL del repo e la licenza (`source`, `license`).
- Le email nelle pagine vengono sostituite con `hello@example.com` e le immagini con
  placeholder `picsum.photos`.
