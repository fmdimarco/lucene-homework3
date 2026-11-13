package it.homework3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Indicizza ricorsivamente tutti i file .txt di una directory.
 *
 * Ogni file diventa un Document con:
 *   file       StringField  (stored)  nome del file con estensione: identificatore esatto, usato nella valutazione
 *   percorso   StoredField  (stored)  percorso relativo, solo per mostrarlo
 *   nome       TextField    (non stored) nome senza estensione, analizzato  -> primo indice (posting list del campo "nome")
 *   contenuto  TextField    (non stored) testo del file, analizzato          -> secondo indice (posting list del campo "contenuto")
 */
public final class Indicizzatore {

    private Indicizzatore() {
    }

    public static void esegui(Path dirDocumenti, Path dirIndice, String tipoAnalyzer, Path csvTempi)
            throws IOException {
        if (!Files.isDirectory(dirDocumenti)) {
            throw new IllegalArgumentException("Directory dei documenti non trovata: " + dirDocumenti);
        }
        Analyzer analyzer = Analizzatori.perCampo(tipoAnalyzer);
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);     // ricostruisce l'indice da zero ad ogni esecuzione

        int indicizzati = 0, vuoti = 0, ignorati = 0, errori = 0;
        long byteLetti = 0, nsLettura = 0;

        long inizio = System.nanoTime();
        List<Path> tutti;
        try (Stream<Path> s = Files.walk(dirDocumenti)) {
            tutti = s.filter(Files::isRegularFile).sorted().toList();
        }

        try (Directory dir = FSDirectory.open(dirIndice);
             IndexWriter writer = new IndexWriter(dir, cfg)) {

            for (Path p : tutti) {
                String nomeFile = p.getFileName().toString();
                if (!nomeFile.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    ignorati++;
                    continue;
                }
                try {
                    long a = System.nanoTime();
                    byte[] byteFile = Files.readAllBytes(p);
                    String testo = decodifica(byteFile);
                    nsLettura += System.nanoTime() - a;
                    byteLetti += byteFile.length;
                    if (testo.isBlank()) {
                        vuoti++;      // file vuoto: viene comunque indicizzato (il nome resta ricercabile)
                    }

                    Document doc = new Document();
                    doc.add(new StringField("file", nomeFile, Field.Store.YES));
                    doc.add(new StoredField("percorso", dirDocumenti.relativize(p).toString()));
                    doc.add(new TextField(Analizzatori.CAMPO_NOME, senzaEstensione(nomeFile), Field.Store.NO));
                    doc.add(new TextField(Analizzatori.CAMPO_CONTENUTO, testo, Field.Store.NO));
                    writer.addDocument(doc);
                    indicizzati++;
                } catch (IOException e) {
                    errori++;
                    System.err.println("Impossibile leggere " + p + ": " + e.getMessage());
                }
            }
            // salva nel commit quale analyzer ha costruito l'indice
            writer.setLiveCommitData(Map.of("analyzer", tipoAnalyzer).entrySet());
            writer.commit();       // senza commit non si vede nulla in ricerca
        }
        long nsTotali = System.nanoTime() - inizio;
        long dimIndice = dimensioneCartella(dirIndice);

        double sec = nsTotali / 1e9;
        System.out.println("=== Indicizzazione completata ===");
        System.out.println("Analyzer contenuto      : " + tipoAnalyzer + " (nome: NomeFileAnalyzer)");
        System.out.println("File .txt indicizzati   : " + indicizzati + " (di cui vuoti: " + vuoti + ")");
        System.out.println("File ignorati (non .txt): " + ignorati);
        System.out.println("Errori di lettura       : " + errori);
        System.out.println(String.format(Locale.ROOT, "Dati letti              : %.2f MB", byteLetti / 1048576.0));
        System.out.println(String.format(Locale.ROOT, "Tempo totale            : %.0f ms (%.2f s)", nsTotali / 1e6, sec));
        System.out.println(String.format(Locale.ROOT, "  di cui lettura file   : %.0f ms", nsLettura / 1e6));
        System.out.println(String.format(Locale.ROOT, "  di cui indicizzazione : %.0f ms (analisi + scrittura + commit)",
                (nsTotali - nsLettura) / 1e6));
        System.out.println(String.format(Locale.ROOT, "Velocita'               : %.0f file/s", indicizzati / sec));
        System.out.println(String.format(Locale.ROOT, "Dimensione indice       : %.2f MB", dimIndice / 1048576.0));

        if (csvTempi != null) {
            boolean nuovo = !Files.exists(csvTempi);
            String riga = String.format(Locale.ROOT, "%s;%s;%d;%d;%.2f;%.0f;%.0f;%.0f;%.0f;%.2f;%s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    tipoAnalyzer, indicizzati, vuoti, byteLetti / 1048576.0,
                    nsTotali / 1e6, nsLettura / 1e6, (nsTotali - nsLettura) / 1e6,
                    indicizzati / sec, dimIndice / 1048576.0, dirDocumenti.getFileName());
            if (nuovo) {
                Files.writeString(csvTempi,
                        "data;analyzer;file_indicizzati;file_vuoti;MB_letti;ms_totali;ms_lettura;ms_indicizzazione;file_al_secondo;MB_indice;cartella\n",
                        StandardOpenOption.CREATE);
            }
            Files.writeString(csvTempi, riga, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("Riga aggiunta a " + csvTempi);
        }
    }

    /** UTF-8 se valido, altrimenti ISO-8859-1 (file creati con vecchi editor/Windows). */
    static String decodifica(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
    }

    static String senzaEstensione(String nomeFile) {
        return nomeFile.substring(0, nomeFile.length() - 4);    // toglie ".txt"
    }

    private static long dimensioneCartella(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }
}
