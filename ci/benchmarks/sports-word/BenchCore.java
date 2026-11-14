package sports.bench;

import java.util.Random;

/** Same immutable corpus and loop for desktop JVM and Android ART. */
public final class BenchCore {
    static final String[] TEXTS = new String[8192], TERMS = new String[8192];
    static volatile long sink;
    static void corpus(String scenario) {
        String[] teams={"new york jets","new england patriots","arsenal","manchester city","inter milan","pga tour","formula 1","nfl","nba","boston celtics","san jose sharks","madrid","wta finals","tennis","disc golf","bears","lions","united states"};
        Random random = new Random(0x14314);
        for (int i=0; i<TEXTS.length; i++) {
            StringBuilder text = new StringBuilder(" live sports today ");
            int count = 2 + random.nextInt(10);
            String selected = teams[random.nextInt(teams.length)];
            if ((i&3)==0) text.append(selected).append(' ');
            for (int j=0; j<count; j++) text.append(teams[random.nextInt(teams.length)]).append(' ');
            text.append(" championship coverage kick off local broadcast ");
            TEXTS[i]=text.toString(); TERMS[i]=selected;
            if (scenario.equals("substring-misses")) {
                TEXTS[i]=" live "+"manchester ".repeat(24)+"football championship ";
                TERMS[i]="man";
            } else if (scenario.equals("overlap-misses")) {
                TEXTS[i]=" "+"a".repeat(512)+" ";
                TERMS[i]="aa";
            }
        }
    }
    static String corpusHash() {
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            for(int i=0;i<TEXTS.length;i++) {
                digest.update(TEXTS[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0);
                digest.update(TERMS[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)10);
            }
            StringBuilder hex=new StringBuilder();
            for(byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static long run(boolean candidate, int repeats) {
        long hits=0;
        for (int repeat=0; repeat<repeats; repeat++) {
            for (int i=0; i<TEXTS.length; i++) {
                if (candidate ? Candidate.INSTANCE.hasWord(TEXTS[i], TERMS[i]) : Baseline.INSTANCE.hasWord(TEXTS[i], TERMS[i])) hits++;
            }
        }
        sink=hits;
        return hits;
    }
    static void verify(long hits, String scenario, int repeats) {
        long expected=scenario.equals("sports") ? 3850L*repeats : 0;
        if(hits!=expected) throw new AssertionError("Unexpected match count: "+hits);
    }
}
