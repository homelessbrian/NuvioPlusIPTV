package sports.bench;

import android.os.Debug;
import java.util.Locale;

public final class ArtRunner {
    private static long allocated() { return Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated")); }
    private static long collections() { return Long.parseLong(Debug.getRuntimeStat("art.gc.gc-count")); }
    public static void main(String[] args) {
        String scenario=args.length==0?"sports":args[0];
        int repeats=scenario.equals("sports")?128:1;
        BenchCore.corpus(scenario);
        System.out.printf("{\"metadata\":true,\"scenario\":\"%s\",\"corpus_sha256\":\"%s\",\"java_version\":\"%s\"}%n",scenario,BenchCore.corpusHash(),System.getProperty("java.version"));
        for(int i=0;i<4;i++) { BenchCore.run(false,repeats); BenchCore.run(true,repeats); }
        for(int pair=0;pair<7;pair++) {
            for(int slot=0;slot<2;slot++) {
                boolean candidate=((pair+slot)&1)!=0;
                long gcBefore=collections(), before=allocated(), at=System.nanoTime();
                long hits=BenchCore.run(candidate,repeats);
                long nanos=System.nanoTime()-at, bytes=allocated()-before, gc=collections()-gcBefore;
                BenchCore.verify(hits,scenario,repeats);
                // Samples outside the timing/allocation interval, not a whole-app peak.
                Runtime runtime=Runtime.getRuntime();
                long heapUsed=runtime.totalMemory()-runtime.freeMemory(), pssKb=Debug.getPss();
                System.out.printf(Locale.ROOT,"{\"runtime\":\"android-art\",\"pair\":%d,\"variant\":\"%s\",\"calls\":%d,\"hits\":%d,\"nanos\":%d,\"allocated_bytes_process\":%d,\"gc_count\":%d,\"heap_used_bytes_after\":%d,\"pss_kb_after\":%d}%n",pair,candidate?"candidate":"baseline",repeats*8192,hits,nanos,bytes,gc,heapUsed,pssKb);
            }
        }
    }
}
