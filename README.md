# Homework 3 – Indicizzazione e ricerca di file .txt con Lucene

Programma Java (Lucene 10) che indicizza ricorsivamente i file `.txt` di una directory su **due campi**
(`nome` e `contenuto`, ciascuno con un analyzer dedicato), interroga l'indice da console e valuta il sistema
con precision, recall, F1 e average precision su un dataset con ground truth.

## Requisiti
- JDK 21 o superiore (`java -version`)
- Maven 3.9+ (`mvn -version`)
- Python 3.8+ (solo per generare il dataset)

## Contenuto del progetto
| File | A cosa serve |
|---|---|
| `genera_dataset.py` | genera N file `.txt` + ground truth (`<dataset>_gt/`) |
| `verifica_dataset.py` | ricontrolla la ground truth rileggendo i file |
| `src/main/java/it/homework3/Analizzatori.java` | analyzer per campo |
| `.../ParserQuery.java` | sintassi delle query (`nome ...` / `contenuto ...`) |
| `.../Indicizzatore.java` | indicizzazione + misura dei tempi |
| `.../Ricerca.java` | ricerca interattiva da console |
| `.../Valutazione.java` | metriche di valutazione |

## Passo 1 – Genera il dataset
```bash
python genera_dataset.py --n 10000 --out dataset_10000
python verifica_dataset.py dataset_10000
```
Per la tabella dei tempi genera anche dataset di dimensioni diverse (opzionale ma consigliato):
```bash
python genera_dataset.py --n 1000  --out dataset_1000
python genera_dataset.py --n 5000  --out dataset_5000
python genera_dataset.py --n 20000 --out dataset_20000
```
Le statistiche (numero file, byte, parole medie…) sono in `dataset_10000_gt/dataset_info.json`.

## Passo 2 – Compila
```bash
mvn -q compile
```

## Passo 3 – Indicizza (due volte: analyzer italiano e standard)
```bash
mvn -q exec:java -Dexec.args="indicizza dataset_10000 indice_italiano italiano"
mvn -q exec:java -Dexec.args="indicizza dataset_10000 indice_standard standard"
```
Su PowerShell metti le virgolette attorno a tutto: `"-Dexec.args=indicizza dataset_10000 indice_italiano italiano"`.
Ogni esecuzione stampa file indicizzati, tempi, MB e dimensione dell'indice, e aggiunge una riga a
`tempi_indicizzazione.csv`. Ripeti per `dataset_1000`, `dataset_5000`, `dataset_20000` (con `italiano`).

## Passo 4 – Valuta
```bash
mvn -q exec:java -Dexec.args="valuta indice_italiano dataset_10000_gt/queries.tsv risultati_italiano.csv"
mvn -q exec:java -Dexec.args="valuta indice_standard dataset_10000_gt/queries.tsv risultati_standard.csv"
```

## Passo 5 – Prova le query da console (le ~10 query "manuali" per la relazione)
```bash
mvn -q exec:java -Dexec.args="cerca indice_italiano 10"
```
Esempi da provare (e confrontare con `indice_standard`):
```
contenuto maratona
contenuto maratone
contenuto "motore di ricerca"
contenuto "motori di ricerca"
contenuto allenamento
contenuto ricetta ingrediente
contenuto investimento risparmio
nome sport
nome "apprendimento automatico"
nome 00042
```
Scrivi `esci` per terminare. In alternativa puoi lanciare `it.homework3.App` dall'IDE con gli stessi argomenti.

## Sintassi delle query
`nome|contenuto  termine1 termine2 "frase esatta"` – i termini sono in AND, le frasi tra virgolette sono phrase query,
ogni termine viene analizzato con l'analyzer del campo scelto.

## Se Maven non trova Lucene 10.2.2
Cerca su Maven Central (`org.apache.lucene:lucene-core`) una versione 10.x esistente e cambia `lucene.version` nel `pom.xml`.
