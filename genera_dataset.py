#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generatore del dataset di file .txt per l'Homework 3 (Lucene).

Idea: il dataset e' SINTETICO ma con una "verita'" (ground truth) nota per costruzione.
Ogni documento ha un argomento (topic) e un concetto principale; nel testo vengono inseriti
alcuni concetti, ciascuno in forma singolare e/o plurale. Il generatore registra quali concetti
(e in quale forma) ha inserito in ogni file, quindi conosce l'insieme COMPLETO dei documenti
rilevanti per ogni query: questo permette di calcolare la recall senza annotazione manuale.

Uso:
    python genera_dataset.py --n 10000 --out dataset_10000
Output:
    dataset_10000/       -> i file .txt (in sottocartelle blocco_XXX) + alcuni file NON .txt da ignorare
    dataset_10000_gt/    -> documenti.tsv, queries.tsv, dataset_info.json (ground truth, fuori dalla cartella indicizzata)
"""
import argparse
import json
import math
import os
import random
import re
import shutil
import sys
from collections import defaultdict

# --------------------------------------------------------------------------------------
# Vocabolario: topic -> { chiave_concetto: (forma singolare, forma plurale) }
# La chiave e' usata anche nel nome del file. Le parole dei concetti hanno almeno 6 lettere
# (singolare e plurale) e non compaiono MAI nelle frasi di riempimento (verificato a runtime).
# --------------------------------------------------------------------------------------
TOPICS = {
    "informatica": {
        "algoritmo": ("algoritmo", "algoritmi"),
        "apprendimento-automatico": ("apprendimento automatico", "apprendimenti automatici"),
        "indicizzazione": ("indicizzazione", "indicizzazioni"),
        "motore-di-ricerca": ("motore di ricerca", "motori di ricerca"),
        "database-relazionale": ("database relazionale", "database relazionali"),
        "compilatore": ("compilatore", "compilatori"),
    },
    "sport": {
        "maratona": ("maratona", "maratone"),
        "allenamento": ("allenamento", "allenamenti"),
        "campionato": ("campionato", "campionati"),
        "squadra": ("squadra", "squadre"),
        "calciatore": ("calciatore", "calciatori"),
        "palestra": ("palestra", "palestre"),
    },
    "cucina": {
        "ricetta": ("ricetta", "ricette"),
        "ingrediente": ("ingrediente", "ingredienti"),
        "impasto": ("impasto", "impasti"),
        "cottura": ("cottura", "cotture"),
        "padella": ("padella", "padelle"),
        "marmellata": ("marmellata", "marmellate"),
    },
    "salute": {
        "vitamina": ("vitamina", "vitamine"),
        "alimentazione": ("alimentazione", "alimentazioni"),
        "visita-medica": ("visita medica", "visite mediche"),
        "terapia": ("terapia", "terapie"),
        "farmaco": ("farmaco", "farmaci"),
        "ospedale": ("ospedale", "ospedali"),
    },
    "viaggi": {
        "aeroporto": ("aeroporto", "aeroporti"),
        "prenotazione": ("prenotazione", "prenotazioni"),
        "itinerario": ("itinerario", "itinerari"),
        "albergo": ("albergo", "alberghi"),
        "biglietto": ("biglietto", "biglietti"),
        "valigia": ("valigia", "valigie"),
    },
    "finanza": {
        "investimento": ("investimento", "investimenti"),
        "obbligazione": ("obbligazione", "obbligazioni"),
        "risparmio": ("risparmio", "risparmi"),
        "finanziamento": ("finanziamento", "finanziamenti"),
        "bilancio": ("bilancio", "bilanci"),
        "dividendo": ("dividendo", "dividendi"),
    },
}

FORME = {k: f for t in TOPICS.values() for k, f in t.items()}          # chiave -> (sing, plur)
TOPIC_DI = {k: t for t, cs in TOPICS.items() for k in cs}               # chiave -> topic
FORMA2CHIAVE = {}
for _k, (_s, _p) in FORME.items():
    FORMA2CHIAVE[_s] = _k
    FORMA2CHIAVE[_p] = _k

# Frasi "neutre": valgono sia per singolare che per plurale (nessun problema di accordo)
NEUTRAL = [
    "Nel testo si parla anche di {c}.",
    "Molti esperti discutono spesso di {c}.",
    "Un capitolo intero è dedicato a {c}.",
    "Tra gli argomenti trattati ci sono {c}.",
    "Un esempio pratico riguarda {c}.",
    "Alcune osservazioni su {c} chiudono la sezione.",
    "Il confronto tra i gruppi parte da {c}.",
    "{C}: questo è il tema ricorrente del documento.",
    "Durante l'incontro si è tornati più volte su {c}.",
]
# Frasi con preposizione articolata elisa (solo singolare che inizia per vocale):
# StandardAnalyzer le tiene come un unico token ("sull'allenamento"), ItalianAnalyzer le "spezza".
ELISION = [
    "Alcune riflessioni sull'{c} meritano attenzione.",
    "Il capitolo parte dall'{c} per arrivare ai casi pratici.",
    "Un esempio concreto nasce dall'{c}.",
    "Nel testo si torna spesso all'{c}.",
    "Molte pagine ruotano intorno all'{c}.",
]

FILLER_TOPIC = {
    "informatica": [
        "Il codice sorgente viene controllato dal gruppo di sviluppo ogni settimana.",
        "Il server risponde in pochi millisecondi anche con molte richieste.",
        "La memoria disponibile limita la dimensione dei dati elaborati.",
        "I programmatori confrontano diverse soluzioni prima di scegliere.",
        "Una buona documentazione riduce gli errori durante la manutenzione.",
        "I controlli periodici segnalano subito ogni regressione.",
    ],
    "sport": [
        "La stagione sportiva inizia a settembre con molte partite importanti.",
        "Il pubblico riempie lo stadio nei fine settimana.",
        "Gli atleti curano il riposo e l'idratazione durante la settimana.",
        "Il preparatore controlla la forma fisica dei giocatori.",
        "Il tifo organizzato accompagna i giocatori in trasferta.",
        "Il regolamento prevede sanzioni per i comportamenti scorretti.",
    ],
    "cucina": [
        "In cucina l'ordine e la pulizia fanno la differenza.",
        "Il sapore migliora se si rispettano i tempi indicati.",
        "Un pizzico di sale basta per esaltare il piatto.",
        "Il piatto viene servito caldo con una guarnizione di erbe.",
        "Gli ospiti apprezzano sempre le porzioni abbondanti.",
        "La dispensa va controllata prima di fare la spesa.",
    ],
    "salute": [
        "Dormire a sufficienza aiuta il corpo a recuperare le energie.",
        "Una passeggiata quotidiana migliora l'umore e la circolazione.",
        "Bere acqua con regolarità è un'abitudine consigliata.",
        "Lo stress prolungato può influire sul benessere generale.",
        "Il riposo è importante quanto l'attività fisica.",
        "Un parere esperto aiuta a distinguere i sintomi lievi da quelli seri.",
    ],
    "viaggi": [
        "Il viaggio comincia con una buona organizzazione dei tempi.",
        "Le città d'arte offrono musei, piazze e mercati.",
        "In estate le spiagge si riempiono di turisti da ogni paese.",
        "Una guida locale racconta la storia dei quartieri antichi.",
        "Il treno attraversa pianure e colline per diverse ore.",
        "I souvenir si comprano di solito nell'ultimo giorno.",
    ],
    "finanza": [
        "I mercati reagiscono rapidamente alle notizie sull'economia.",
        "Il tasso di interesse influenza le scelte delle famiglie.",
        "Una buona pianificazione evita spese impreviste.",
        "Il consulente illustra rischi e rendimenti con esempi chiari.",
        "L'inflazione riduce il potere d'acquisto nel tempo.",
        "Le imprese pubblicano i risultati ogni trimestre.",
    ],
}
FILLER_GENERIC = [
    "Questo documento fa parte di una raccolta creata per un esperimento universitario.",
    "Le frasi sono generate in modo casuale a partire da modelli prefissati.",
    "Il contenuto ha solo valore dimostrativo e non descrive fatti reali.",
    "Ogni file è identificato da un numero progressivo nel nome.",
    "Alla fine del testo si trova un breve riepilogo.",
    "Le sezioni successive riprendono gli stessi temi con altre parole.",
    "Non tutti i lettori concordano con questa impostazione.",
    "In generale, i risultati dipendono dal contesto e dai dati disponibili.",
    "Una lettura attenta permette di cogliere i dettagli più importanti.",
    "Si consiglia di confrontare più fonti prima di trarre conclusioni.",
]

# File NON .txt (da ignorare): se venissero indicizzati per errore comparirebbero come falsi positivi
NON_TXT = [
    ("nota_1.md", "Appunti su algoritmo e maratona.\n"),
    ("nota_2.md", "Ricetta con ingrediente e padella.\n"),
    ("tabella.csv", "campo;valore\nalgoritmo;1\nmaratona;2\n"),
    ("registro.log", "investimento risparmio aeroporto\n"),
    ("appunti.txt.bak", "vitamina farmaco algoritmo\n"),
]

# Query di valutazione (il testo usa la sintassi del programma: parola chiave nome|contenuto + termini)
SINGOLE_SING = ["algoritmo", "maratona", "allenamento", "ricetta", "vitamina",
                "aeroporto", "investimento", "indicizzazione"]
SINGOLE_PLUR = ["algoritmo", "maratona", "allenamento", "ricetta"]
FRASI_SING = ["apprendimento-automatico", "motore-di-ricerca", "visita-medica", "database-relazionale"]
FRASI_PLUR = ["apprendimento-automatico", "motore-di-ricerca"]
COPPIE_AND = [("maratona", "allenamento"), ("ricetta", "ingrediente"), ("investimento", "risparmio"),
              ("algoritmo", "indicizzazione"), ("vitamina", "alimentazione"), ("algoritmo", "aeroporto")]
NOME_TOPIC = ["sport", "informatica", "cucina"]
NOME_CONC = ["maratona", "algoritmo", "ricetta"]
NOME_FRASI = ["apprendimento-automatico", "motore-di-ricerca"]
NOME_AND = [("sport", "maratona"), ("informatica", "algoritmo")]
NOME_ID = [42, 7]


def verifica_vocabolario():
    """Controlla che le parole dei concetti non compaiano nelle frasi di riempimento
    e non siano condivise tra concetti diversi (altrimenti la ground truth sarebbe sporca)."""
    parola2chiave = {}
    for chiave, (s, p) in FORME.items():
        for forma in (s, p):
            for w in re.findall(r"\w+", forma.lower()):
                if w == "di":
                    continue
                if parola2chiave.setdefault(w, chiave) != chiave:
                    sys.exit(f"Parola '{w}' condivisa tra concetti ({parola2chiave[w]} e {chiave})")
    frasi = (FILLER_GENERIC + NEUTRAL + ELISION
             + [f for fs in FILLER_TOPIC.values() for f in fs])
    for frase in frasi:
        for w in re.findall(r"\w+", frase.lower()):
            if w in parola2chiave:
                sys.exit(f"La parola '{w}' (concetto {parola2chiave[w]}) compare in: {frase}")


def frase_con_concetto(rng, chiave, usate):
    sing, plur = FORME[chiave]
    tipo = rng.choice("sp")
    c = sing if tipo == "s" else plur
    usate.add(tipo)
    if tipo == "s" and c[0] in "aeiou" and rng.random() < 0.4:
        return rng.choice(ELISION).format(c=c)
    return rng.choice(NEUTRAL).format(c=c, C=c[0].upper() + c[1:])


def costruisci_documento(rng, topic, main):
    concetti = [main]
    altri = [k for k in TOPICS[topic] if k != main]
    concetti += rng.sample(altri, rng.choice([0, 0, 1, 1, 2]))
    if rng.random() < 0.12:  # rumore: un concetto di un altro topic
        t2 = rng.choice([t for t in TOPICS if t != topic])
        concetti.append(rng.choice(list(TOPICS[t2])))
    n_frasi = max(8, min(120, int(rng.lognormvariate(math.log(22), 0.5))))
    usate = defaultdict(set)
    frasi = []
    for k in concetti:
        occorrenze = rng.randint(2, 4) if k == main else rng.randint(1, 3)
        for _ in range(occorrenze):
            frasi.append(frase_con_concetto(rng, k, usate[k]))
    while len(frasi) < n_frasi:
        pool = FILLER_TOPIC[topic] if rng.random() < 0.6 else FILLER_GENERIC
        frasi.append(rng.choice(pool))
    rng.shuffle(frasi)
    paragrafi, i = [], 0
    while i < len(frasi):
        k = rng.randint(3, 6)
        paragrafi.append(" ".join(frasi[i:i + k]))
        i += k
    testo = "\n\n".join(paragrafi) + "\n"
    return testo, {k: "".join(sorted(v)) for k, v in usate.items()}


def costruisci_queries(recs, n, larghezza):
    docs_conc, docs_main, docs_topic, docs_id = defaultdict(set), defaultdict(set), defaultdict(set), {}
    for r in recs:
        docs_topic[r["topic"]].add(r["file"])
        docs_main[r["main"]].add(r["file"])
        docs_id[r["id"]] = r["file"]
        for k in r["concetti"]:
            docs_conc[k].add(r["file"])
    Q = []
    saltate = []

    def add(testo, rel):
        if rel:
            Q.append((testo, sorted(rel)))
        else:
            saltate.append(testo)

    for k in SINGOLE_SING:
        add(f"contenuto {FORME[k][0]}", docs_conc[k])
    for k in SINGOLE_PLUR:
        add(f"contenuto {FORME[k][1]}", docs_conc[k])
    for k in FRASI_SING:
        add(f'contenuto "{FORME[k][0]}"', docs_conc[k])
    for k in FRASI_PLUR:
        add(f'contenuto "{FORME[k][1]}"', docs_conc[k])
    for a, b in COPPIE_AND:
        add(f"contenuto {FORME[a][0]} {FORME[b][0]}", docs_conc[a] & docs_conc[b])
    for t in NOME_TOPIC:
        add(f"nome {t}", docs_topic[t])
    for k in NOME_CONC:
        add(f"nome {FORME[k][0]}", docs_main[k])
    for k in NOME_FRASI:
        add(f'nome "{FORME[k][0]}"', docs_main[k])
    for t, k in NOME_AND:
        add(f"nome {t} {FORME[k][0]}", docs_topic[t] & docs_main[k])
    for i in NOME_ID:
        if i in docs_id:
            add(f"nome {i:0{larghezza}d}", {docs_id[i]})
    return Q, saltate


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, default=10000, help="numero di file .txt (default 10000)")
    ap.add_argument("--out", default=None, help="cartella dei documenti (default dataset_<n>)")
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--force", action="store_true", help="sovrascrive le cartelle esistenti")
    a = ap.parse_args()

    verifica_vocabolario()
    out = a.out or f"dataset_{a.n}"
    gt = out.rstrip("/\\") + "_gt"
    for d in (out, gt):
        if os.path.exists(d):
            if not a.force:
                sys.exit(f"{d} esiste gia' (usa --force per sovrascrivere)")
            shutil.rmtree(d)
        os.makedirs(d)

    rng = random.Random(a.seed)
    n = a.n
    larghezza = max(5, len(str(n)))
    n_vuoti = min(max(2, n // 2000), max(0, n // 10))
    vuoti = set(rng.sample(range(1, n + 1), n_vuoti))
    topics = list(TOPICS)

    recs = []
    byte_tot = parole_tot = 0
    for i in range(1, n + 1):
        topic = rng.choice(topics)
        main_k = rng.choice(list(TOPICS[topic]))
        nome = f"{topic}_{main_k}_{i:0{larghezza}d}.txt"
        blocco = f"blocco_{(i - 1) // 500:03d}"
        os.makedirs(os.path.join(out, blocco), exist_ok=True)
        if i in vuoti:
            testo, concetti = "", {}
        else:
            testo, concetti = costruisci_documento(rng, topic, main_k)
        with open(os.path.join(out, blocco, nome), "w", encoding="utf-8", newline="\n") as f:
            f.write(testo)
        byte_tot += len(testo.encode("utf-8"))
        parole_tot += len(testo.split())
        recs.append({"id": i, "file": nome, "percorso": f"{blocco}/{nome}", "topic": topic,
                     "main": main_k, "vuoto": int(i in vuoti), "concetti": concetti})

    for nome, contenuto in NON_TXT:
        with open(os.path.join(out, nome), "w", encoding="utf-8", newline="\n") as f:
            f.write(contenuto)

    with open(os.path.join(gt, "documenti.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("file\tpercorso\ttopic\tconcetto_principale\tvuoto\tconcetti_nel_testo\n")
        for r in recs:
            conc = ";".join(f"{k}:{v}" for k, v in sorted(r["concetti"].items()))
            f.write(f"{r['file']}\t{r['percorso']}\t{r['topic']}\t{r['main']}\t{r['vuoto']}\t{conc}\n")

    Q, saltate = costruisci_queries(recs, n, larghezza)
    with open(os.path.join(gt, "queries.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# id\tquery\tn_rilevanti\tfile_rilevanti (separati da spazio)\n")
        for j, (testo, rel) in enumerate(Q, 1):
            f.write(f"Q{j:02d}\t{testo}\t{len(rel)}\t{' '.join(rel)}\n")

    n_con_testo = n - len(vuoti)
    info = {
        "seed": a.seed, "file_txt": n, "file_txt_vuoti": len(vuoti), "file_non_txt_da_ignorare": len(NON_TXT),
        "documenti_con_testo": n_con_testo, "byte_totali": byte_tot,
        "parole_medie_per_documento": round(parole_tot / max(1, n_con_testo), 1),
        "sottocartelle": (n - 1) // 500 + 1, "topic": topics,
        "concetti_per_topic": {t: len(c) for t, c in TOPICS.items()},
        "query_di_valutazione": len(Q), "query_saltate_perche_senza_rilevanti": saltate,
    }
    with open(os.path.join(gt, "dataset_info.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=2)

    print(f"Creati {n} file .txt in '{out}' ({len(vuoti)} vuoti) + {len(NON_TXT)} file non .txt da ignorare")
    print(f"Ground truth in '{gt}': {len(Q)} query di valutazione")
    if saltate:
        print("Query senza documenti rilevanti (saltate):", saltate)
    print(json.dumps(info, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
