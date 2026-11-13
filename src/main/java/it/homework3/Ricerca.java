package it.homework3;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/** Legge query da console, interroga l'indice e stampa i primi risultati con il punteggio BM25. */
public final class Ricerca {

    private Ricerca() {
    }

    public static void esegui(Path dirIndice, int topN) throws Exception {
        try (Directory dir = FSDirectory.open(dirIndice);
             DirectoryReader reader = DirectoryReader.open(dir)) {

            String tipo = Analizzatori.tipoIndice(reader);
            ParserQuery parser = new ParserQuery(Analizzatori.perCampo(tipo));
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());          // e' gia' il default, esplicitato per chiarezza
            StoredFields stored = searcher.storedFields();

            System.out.println("Indice aperto: " + reader.numDocs() + " documenti, analyzer contenuto = " + tipo);
            System.out.println("Sintassi: nome <termini>  |  contenuto <termini>   (frasi tra virgolette, es. contenuto \"motore di ricerca\")");
            System.out.println("Scrivi 'esci' per terminare.");

            Charset cs = Charset.forName(System.getProperty("stdin.encoding", Charset.defaultCharset().name()));
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, cs));
            while (true) {
                System.out.print("\nquery> ");
                System.out.flush();
                String riga = in.readLine();
                if (riga == null || riga.trim().equalsIgnoreCase("esci")) {
                    break;
                }
                if (riga.isBlank()) {
                    continue;
                }
                try {
                    Query query = parser.parse(riga);
                    long t0 = System.nanoTime();
                    int totale = searcher.count(query);
                    TopDocs top = searcher.search(query, topN);
                    double ms = (System.nanoTime() - t0) / 1e6;

                    System.out.println("Query Lucene: " + query);
                    System.out.println(String.format(Locale.ROOT, "%d risultati (%.1f ms) - primi %d:",
                            totale, ms, top.scoreDocs.length));
                    int pos = 1;
                    for (ScoreDoc sd : top.scoreDocs) {
                        Document d = stored.document(sd.doc, Set.of("file", "percorso"));
                        System.out.println(String.format(Locale.ROOT, "%3d. %.3f  %s   [%s]",
                                pos++, sd.score, d.get("file"), d.get("percorso")));
                    }
                } catch (IllegalArgumentException e) {
                    System.out.println("Errore: " + e.getMessage());
                }
            }
        }
    }
}
