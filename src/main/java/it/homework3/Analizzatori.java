package it.homework3;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.index.DirectoryReader;

/**
 * Scelta degli analyzer.
 *
 * - campo "nome": NomeFileAnalyzer (char filter che trasforma _ - . in spazio, StandardTokenizer,
 *   lowercase, ASCII folding). Niente stemming e niente stopword: il nome e' un insieme di
 *   etichette, non prosa.
 * - campo "contenuto": ItalianAnalyzer (elisioni, lowercase, stopword italiane, stemmer leggero)
 *   oppure StandardAnalyzer (usato come termine di confronto nella valutazione).
 */
public final class Analizzatori {

    public static final String CAMPO_NOME = "nome";
    public static final String CAMPO_CONTENUTO = "contenuto";
    public static final String ITALIANO = "italiano";
    public static final String STANDARD = "standard";

    private Analizzatori() {
    }

    public static Analyzer contenuto(String tipo) {
        return switch (tipo) {
            case ITALIANO -> new ItalianAnalyzer();
            case STANDARD -> new StandardAnalyzer();
            default -> throw new IllegalArgumentException("Analyzer sconosciuto: " + tipo
                    + " (usa '" + ITALIANO + "' oppure '" + STANDARD + "')");
        };
    }

    /** Analyzer complessivo: uno diverso per ciascun campo. Da usare sia in indicizzazione sia in ricerca. */
    public static Analyzer perCampo(String tipo) {
        return new PerFieldAnalyzerWrapper(
                new StandardAnalyzer(),
                Map.of(CAMPO_NOME, new NomeFileAnalyzer(),
                       CAMPO_CONTENUTO, contenuto(tipo)));
    }

    /** Legge dal commit dell'indice quale analyzer lo ha costruito (cosi' la ricerca usa quello giusto). */
    public static String tipoIndice(DirectoryReader reader) throws IOException {
        String t = reader.getIndexCommit().getUserData().get("analyzer");
        return t == null ? ITALIANO : t;
    }

   
    static final class NomeFileAnalyzer extends Analyzer {
        private static final Pattern SEPARATORI = Pattern.compile("[_\\-.]");

        @Override
        protected Reader initReader(String fieldName, Reader reader) {
            return new PatternReplaceCharFilter(SEPARATORI, " ", reader);
        }

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer sorgente = new StandardTokenizer();
            TokenStream flusso = new LowerCaseFilter(sorgente);
            flusso = new ASCIIFoldingFilter(flusso);
            return new TokenStreamComponents(sorgente, flusso);
        }
    }
}
