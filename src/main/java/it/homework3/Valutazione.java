package it.homework3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Valuta l'indice con una ground truth (file queries.tsv prodotto da genera_dataset.py).
 *
 * Per ogni query si recuperano TUTTI i documenti che la soddisfano (non solo i primi k) e si calcolano:
 *   precision = TP / recuperati        recall = TP / rilevanti        F1 = media armonica
 *   AP (average precision) = media delle precision ai rank dei documenti rilevanti (misura la qualita' del ranking BM25)
 * Poi si fa la media (macro) su tutte le query e per gruppo (nome / contenuto).
 */
public final class Valutazione {

    private record Interrogazione(String id, String testo, Set<String> rilevanti) {
        String campo() {
            return testo.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        }
    }

    private record Esito(Interrogazione q, int recuperati, int tp, double precision, double recall,
                         double f1, double ap, double ms, List<String> mancati, List<String> inPiu) {
    }

    private Valutazione() {
    }

    public static void esegui(Path dirIndice, Path fileQueries, Path csvOut) throws Exception {
        List<Interrogazione> queries = leggiQueries(fileQueries);

        try (Directory dir = FSDirectory.open(dirIndice);
             DirectoryReader reader = DirectoryReader.open(dir)) {

            String tipo = Analizzatori.tipoIndice(reader);
            ParserQuery parser = new ParserQuery(Analizzatori.perCampo(tipo));
            IndexSearcher searcher = new IndexSearcher(reader);
            StoredFields stored = searcher.storedFields();
            int maxDoc = Math.max(1, reader.maxDoc());   // n >= numero documenti => si recuperano tutte le corrispondenze

            // riscaldamento (JIT e cache del file system) per rendere i tempi per-query piu' stabili
            for (Interrogazione q : queries) {
                searcher.search(parser.parse(q.testo()), maxDoc);
            }

            List<Esito> esiti = new ArrayList<>();
            for (Interrogazione q : queries) {
                Query query = parser.parse(q.testo());
                long t0 = System.nanoTime();
                TopDocs top = searcher.search(query, maxDoc);
                double ms = (System.nanoTime() - t0) / 1e6;

                int tp = 0, rank = 0;
                double sommaPrecisioni = 0;
                Set<String> recuperatiSet = new HashSet<>();
                List<String> inPiu = new ArrayList<>();
                for (ScoreDoc sd : top.scoreDocs) {
                    rank++;
                    String file = stored.document(sd.doc, Set.of("file")).get("file");
                    recuperatiSet.add(file);
                    if (q.rilevanti().contains(file)) {
                        tp++;
                        sommaPrecisioni += (double) tp / rank;
                    } else if (inPiu.size() < 3) {
                        inPiu.add(file);
                    }
                }
                List<String> mancati = new ArrayList<>();
                for (String r : q.rilevanti()) {
                    if (!recuperatiSet.contains(r) && mancati.size() < 3) {
                        mancati.add(r);
                    }
                }
                int recuperati = top.scoreDocs.length;
                double precision = recuperati == 0 ? 0 : (double) tp / recuperati;
                double recall = (double) tp / q.rilevanti().size();
                double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);
                double ap = sommaPrecisioni / q.rilevanti().size();
                esiti.add(new Esito(q, recuperati, tp, precision, recall, f1, ap, ms, mancati, inPiu));
            }

            stampa(esiti, tipo, reader.numDocs());
            if (csvOut != null) {
                scriviCsv(esiti, tipo, csvOut);
                System.out.println("\nRisultati per query salvati in " + csvOut);
            }
        }
    }

    private static List<Interrogazione> leggiQueries(Path file) throws IOException {
        List<Interrogazione> res = new ArrayList<>();
        for (String riga : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (riga.isBlank() || riga.startsWith("#")) {
                continue;
            }
            String[] c = riga.split("\t", -1);
            Set<String> rel = new HashSet<>(Arrays.asList(c[3].trim().split("\\s+")));
            res.add(new Interrogazione(c[0], c[1], rel));
        }
        return res;
    }

    private static void stampa(List<Esito> esiti, String tipo, int numDocs) {
        System.out.println("=== Valutazione: indice con " + numDocs + " documenti, analyzer contenuto = " + tipo + " ===");
        System.out.println(String.format(Locale.ROOT, "%-4s %-42s %6s %6s %6s %6s %6s %6s %6s %8s",
                "id", "query", "rilev", "recup", "TP", "P", "R", "F1", "AP", "ms"));
        for (Esito e : esiti) {
            String testo = e.q().testo();
            if (testo.length() > 42) {
                testo = testo.substring(0, 39) + "...";
            }
            System.out.println(String.format(Locale.ROOT,
                    "%-4s %-42s %6d %6d %6d %6.3f %6.3f %6.3f %6.3f %8.2f",
                    e.q().id(), testo, e.q().rilevanti().size(), e.recuperati(), e.tp(),
                    e.precision(), e.recall(), e.f1(), e.ap(), e.ms()));
        }

        System.out.println("\n--- Medie (macro) ---");
        Map<String, List<Esito>> gruppi = new LinkedHashMap<>();
        gruppi.put("contenuto", new ArrayList<>());
        gruppi.put("nome", new ArrayList<>());
        for (Esito e : esiti) {
            gruppi.computeIfAbsent(e.q().campo().startsWith("cont") ? "contenuto" : e.q().campo(),
                    k -> new ArrayList<>()).add(e);
        }
        gruppi.put("TUTTE", esiti);
        System.out.println(String.format(Locale.ROOT, "%-10s %5s %7s %7s %7s %7s %9s",
                "gruppo", "n", "P", "R", "F1", "MAP", "ms/query"));
        for (Map.Entry<String, List<Esito>> g : gruppi.entrySet()) {
            List<Esito> l = g.getValue();
            if (l.isEmpty()) {
                continue;
            }
            System.out.println(String.format(Locale.ROOT, "%-10s %5d %7.3f %7.3f %7.3f %7.3f %9.2f",
                    g.getKey(), l.size(), media(l, 0), media(l, 1), media(l, 2), media(l, 3), media(l, 4)));
        }

        System.out.println("\n--- Query con recall < 1 o precision < 1 (esempi di errori) ---");
        boolean nessuna = true;
        for (Esito e : esiti) {
            if (e.recall() < 1.0 || e.precision() < 1.0) {
                nessuna = false;
                System.out.println(String.format(Locale.ROOT, "%s  %s   (R=%.3f, P=%.3f)",
                        e.q().id(), e.q().testo(), e.recall(), e.precision()));
                if (!e.mancati().isEmpty()) {
                    System.out.println("     rilevanti NON trovati (es.): " + e.mancati());
                }
                if (!e.inPiu().isEmpty()) {
                    System.out.println("     trovati ma NON rilevanti (es.): " + e.inPiu());
                }
            }
        }
        if (nessuna) {
            System.out.println("(nessuna: precision e recall valgono 1 su tutte le query)");
        }
    }

    /** 0=P, 1=R, 2=F1, 3=AP, 4=ms */
    private static double media(List<Esito> l, int metrica) {
        double s = 0;
        for (Esito e : l) {
            s += switch (metrica) {
                case 0 -> e.precision();
                case 1 -> e.recall();
                case 2 -> e.f1();
                case 3 -> e.ap();
                default -> e.ms();
            };
        }
        return s / l.size();
    }

    private static void scriviCsv(List<Esito> esiti, String tipo, Path out) throws IOException {
        StringBuilder sb = new StringBuilder("analyzer;id;query;rilevanti;recuperati;TP;precision;recall;f1;AP;ms\n");
        for (Esito e : esiti) {
            sb.append(String.format(Locale.ROOT, "%s;%s;\"%s\";%d;%d;%d;%.4f;%.4f;%.4f;%.4f;%.3f%n",
                    tipo, e.q().id(), e.q().testo().replace("\"", "'"), e.q().rilevanti().size(),
                    e.recuperati(), e.tp(), e.precision(), e.recall(), e.f1(), e.ap(), e.ms()));
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
