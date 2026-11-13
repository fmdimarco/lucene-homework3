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
Per la tabella dei tempi si possono generare anche dataset di dimensioni diverse:
```bash
python genera_dataset.py --n 1000  --out dataset_1000
python genera_dataset.py --n 5000  --out dataset_5000
python genera_dataset.py --n 20000 --out dataset_20000
```
Le statistiche (numero file, byte, parole medie…) sono in `dataset_10000_gt/dataset_info.json`.

## Passo 2 – Compilazione
```bash
mvn -q compile
```

## Passo 3 – Indicizzazione (due volte: analyzer italiano e standard)
```bash
mvn -q exec:java -Dexec.args="indicizza dataset_10000 indice_italiano italiano"
mvn -q exec:java -Dexec.args="indicizza dataset_10000 indice_standard standard"
```

## Passo 4 – Valutazione
```bash
mvn -q exec:java -Dexec.args="valuta indice_italiano dataset_10000_gt/queries.tsv risultati_italiano.csv"
mvn -q exec:java -Dexec.args="valuta indice_standard dataset_10000_gt/queries.tsv risultati_standard.csv"
```
Scrivi `esci` per terminare.

## Sintassi delle query
`nome|contenuto  termine1 termine2 "frase esatta"` – i termini sono in AND, le frasi tra virgolette sono phrase query,
ogni termine viene analizzato con l'analyzer del campo scelto.
