package it.homework3;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.QueryBuilder;

/**
 * Sintassi delle query:
 *
 *   nome      termine1 termine2 "frase esatta" ...
 *   contenuto termine1 termine2 "frase esatta" ...
 *
 * - la prima parola indica il campo (nome oppure contenuto);
 * - i termini senza virgolette sono in AND (devono comparire tutti);
 * - le parole tra virgolette formano una phrase query (stessa sequenza, posizioni consecutive);
 * - ogni termine viene analizzato con l'analyzer del campo scelto, quindi la query
 *   "contenuto Maratone" con ItalianAnalyzer e' equivalente a cercare la radice "maraton".
 */
public final class ParserQuery {

    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private final QueryBuilder costruttore;

    public ParserQuery(Analyzer analyzerPerCampo) {
        this.costruttore = new QueryBuilder(analyzerPerCampo);
    }

    public Query parse(String input) {
        String s = input.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Query vuota");
        }
        int spazio = indiceSpazio(s);
        String parolaChiave = (spazio < 0 ? s : s.substring(0, spazio)).toLowerCase(Locale.ROOT);
        String resto = spazio < 0 ? "" : s.substring(spazio + 1);

        String campo = switch (parolaChiave) {
            case "nome" -> Analizzatori.CAMPO_NOME;
            case "contenuto", "contentuto" -> Analizzatori.CAMPO_CONTENUTO;   // "contentuto" e' il refuso della traccia
            default -> throw new IllegalArgumentException(
                    "La query deve iniziare con 'nome' o 'contenuto' (trovato: '" + parolaChiave + "')");
        };

        BooleanQuery.Builder bq = new BooleanQuery.Builder();
        int clausole = 0;
        Matcher m = TOKEN.matcher(resto);
        while (m.find()) {
            Query q;
            if (m.group(1) != null) {
                q = costruttore.createPhraseQuery(campo, m.group(1));
            } else {
                q = costruttore.createBooleanQuery(campo, m.group(2), BooleanClause.Occur.MUST);
            }
            if (q != null) {          // null = il termine e' sparito dopo l'analisi (es. stopword)
                bq.add(q, BooleanClause.Occur.MUST);
                clausole++;
            }
        }
        if (clausole == 0) {
            throw new IllegalArgumentException(
                    "Nessun termine utile dopo l'analisi (solo stopword o query vuota?)");
        }
        return bq.build();
    }

    private static int indiceSpazio(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
