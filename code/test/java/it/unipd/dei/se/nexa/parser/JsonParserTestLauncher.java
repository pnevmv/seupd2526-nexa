package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.StringReader;

public class JsonParserTestLauncher {

    public static void main(String[] args) {
        String pubJsonInput = "[" +
                "  {" +
                "    \"pubkey\": 999," +
                "    \"title\": \"Stress Test Evoluto\"," +
                "    \"abstract\": \"Abstract: Benvenuti su https://nexa.dei.unipd.it! <p>Tag HTML rimosso.</p> " +
                "Citazioni quadre [1, 2-5] e citazioni autore (Rossi et al., 2023). " +
                "Entità HTML: p &lt; 0.05 &amp; spazio&nbsp;unito. " +
                "Simboli: 37°C e 0.5±0.1. Sezioni: Methods: i risultati sono ottimi. " +
                "Emoji finali: 🚀🔥🏥🧪\"," +
                "    \"venue\": \"Journal of AI Stress Tests\"," +
                "    \"authors\": \"A. Collaborator, B. Assistant\"" +
                "  }" +
                "]";

        String claimJsonInput = "[" +
                "  {" +
                "    \"index\":5," +
                "    \"text\":\"@KYT_ThatsME @EricTopol @NathanGrubaugh @angie_rasmussen @DrDenaGrayson @Laurie_Garrett @R_H_Ebright @Ayjchan @DrEricDing @DrZoeHyde CDC EVALI study only monitored cases w\\\\/ a record of vaping in previous 90 days & with seriousness necessitating hospital admission. But at the time of the first outbreak in WI & IL, there were 2x the ER visits for mysterious, critical lung disease in 11-34yo. 🚨\"," +
                "    \"pubkey\":8474" +
                "  }" +
                "]";

        System.out.println("=== TEST PUBLICATION PARSER ===");
        try (StringReader reader = new StringReader(pubJsonInput)) {
            JsonParser p = new JsonParser(reader);
            for (Publication d : p) {
                System.out.printf("%n%s%n", d);
            }
        } catch (Exception e) {
            System.err.println("Error during Publication parsing: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n=== TEST CLAIM PARSER ===");
        try {
            ObjectMapper mapper = new ObjectMapper();
            Claim[] claims = mapper.readValue(claimJsonInput, Claim[].class);
            for (Claim c : claims) {
                System.out.printf("%n%s%n", c);
            }
        } catch (Exception e) {
            System.err.println("Error during Claim parsing: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
