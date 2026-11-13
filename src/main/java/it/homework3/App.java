package it.homework3;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Punto di ingresso unico.
 *
 *   indicizza <dir_documenti> <dir_indice> [italiano|standard] [file_tempi.csv]
 *   cerca     <dir_indice> [numero_risultati]
 *   valuta    <dir_indice> <queries.tsv> [risultati.csv]
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            uso();
            return;
        }
        switch (args[0]) {
            case "indicizza" -> {
                if (args.length < 3) {
                    uso();
                    return;
                }
                String analyzer = args.length > 3 ? args[3] : Analizzatori.ITALIANO;
                Path csv = args.length > 4 ? Paths.get(args[4]) : Paths.get("tempi_indicizzazione.csv");
                Indicizzatore.esegui(Paths.get(args[1]), Paths.get(args[2]), analyzer, csv);
            }
            case "cerca" -> {
                if (args.length < 2) {
                    uso();
                    return;
                }
                int n = args.length > 2 ? Integer.parseInt(args[2]) : 10;
                Ricerca.esegui(Paths.get(args[1]), n);
            }
            case "valuta" -> {
                if (args.length < 3) {
                    uso();
                    return;
                }
                Path csv = args.length > 3 ? Paths.get(args[3]) : null;
                Valutazione.esegui(Paths.get(args[1]), Paths.get(args[2]), csv);
            }
            default -> uso();
        }
    }

    private static void uso() {
        System.out.println("""
                Uso:
                  indicizza <dir_documenti> <dir_indice> [italiano|standard] [file_tempi.csv]
                  cerca     <dir_indice> [numero_risultati]
                  valuta    <dir_indice> <queries.tsv> [risultati.csv]
                """);
    }
}
