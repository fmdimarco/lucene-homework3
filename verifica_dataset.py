#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Verifica indipendente della ground truth: rilegge i file .txt, cerca con espressioni regolari
le forme dei concetti e ricalcola gli insiemi di documenti rilevanti di ogni query.
Se tutto torna, la ground truth usata per calcolare la recall e' affidabile.

Uso:  python verifica_dataset.py dataset_10000
(legge anche dataset_10000_gt/documenti.tsv e dataset_10000_gt/queries.tsv)
"""
import os
import re
import sys

from genera_dataset import FORME, FORMA2CHIAVE


def main():
    if len(sys.argv) < 2:
        sys.exit("Uso: python verifica_dataset.py <cartella_dataset>")
    out = sys.argv[1].rstrip("/\\")
    gt = out + "_gt"

    regex = {k: [(tipo, re.compile(r"\b" + re.escape(f) + r"\b", re.IGNORECASE))
                 for tipo, f in zip("sp", forme)] for k, forme in FORME.items()}

    # 1) scansione dei file
    trovati = {}      # nome file -> {chiave: "sp"}
    percorsi = {}
    for radice, _, files in os.walk(out):
        for nome in files:
            if not nome.lower().endswith(".txt"):
                continue
            percorsi[nome] = os.path.join(radice, nome)
    for nome, p in percorsi.items():
        with open(p, encoding="utf-8") as f:
            testo = f.read()
        d = {}
        for k, lst in regex.items():
            tipi = "".join(sorted(t for t, rx in lst if rx.search(testo)))
            if tipi:
                d[k] = tipi
        trovati[nome] = d

    # 2) confronto con documenti.tsv
    errori = 0
    attesi = {}
    with open(os.path.join(gt, "documenti.tsv"), encoding="utf-8") as f:
        next(f)
        for riga in f:
            c = riga.rstrip("\n").split("\t")
            conc = {}
            if len(c) > 5 and c[5]:
                for voce in c[5].split(";"):
                    k, v = voce.split(":")
                    conc[k] = v
            attesi[c[0]] = conc
    if set(attesi) != set(trovati):
        print(f"ERRORE: file nel dataset ({len(trovati)}) diversi da quelli in documenti.tsv ({len(attesi)})")
        errori += 1
    for nome, conc in attesi.items():
        if trovati.get(nome) != conc:
            errori += 1
            if errori <= 5:
                print(f"ERRORE {nome}: attesi {conc}, trovati {trovati.get(nome)}")
    print(f"[1] Concetti per documento: {len(attesi)} documenti controllati, "
          f"{'OK' if errori == 0 else str(errori) + ' discrepanze'}")

    # 3) ricalcolo delle query dalla scansione dei file (senza usare i metadati)
    tot_q = err_q = 0
    with open(os.path.join(gt, "queries.tsv"), encoding="utf-8") as f:
        for riga in f:
            if riga.startswith("#") or not riga.strip():
                continue
            qid, testo, _, rel = riga.rstrip("\n").split("\t")
            rel = set(rel.split())
            campo, _, resto = testo.partition(" ")
            termini = [a or b for a, b in re.findall(r'"([^"]*)"|(\S+)', resto)]
            if campo == "contenuto":
                chiavi = [FORMA2CHIAVE[t.lower()] for t in termini]
                calc = {n for n, d in trovati.items() if all(k in d for k in chiavi)}
            else:
                calc = set()
                for n in percorsi:
                    base = n[:-4].lower()
                    ok = True
                    for t in termini:
                        if re.fullmatch(r"\d+", t):
                            ok &= base.endswith("_" + t)
                        elif t.lower() in TOPIC_NOMI:
                            ok &= base.startswith(t.lower() + "_")
                        else:
                            ok &= ("_" + t.lower().replace(" ", "-") + "_") in base
                    if ok:
                        calc.add(n)
            tot_q += 1
            if calc != rel:
                err_q += 1
                print(f"ERRORE {qid} ({testo}): attesi {len(rel)}, ricalcolati {len(calc)}")
    print(f"[2] Query ricalcolate: {tot_q}, {'tutte OK' if err_q == 0 else str(err_q) + ' con errori'}")
    sys.exit(1 if (errori or err_q) else 0)


from genera_dataset import TOPICS  # noqa: E402
TOPIC_NOMI = set(TOPICS)

if __name__ == "__main__":
    main()
