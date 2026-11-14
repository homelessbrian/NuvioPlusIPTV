package sports.bench;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import com.sun.management.ThreadMXBean;

public final class JvmRunner {
    public static void main(String[] args) {
        String scenario=args.length==0?"sports":args[0];
        int repeats=scenario.equals("sports")?128:1;
        BenchCore.corpus(scenario);
        System.out.printf("{\"metadata\":true,\"scenario\":\"%s\",\"corpus_sha256\":\"%s\",\"java_version\":\"%s\"}%n",scenario,BenchCore.corpusHash(),System.getProperty("java.version"));
        ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<4;i++) { BenchCore.run(false,repeats); BenchCore.run(true,repeats); }
        for(int pair=0;pair<7;pair++) {
            for(int slot=0;slot<2;slot++) {
                boolean candidate=((pair+slot)&1)!=0;
                long id=Thread.currentThread().getId(), before=bean.getThreadAllocatedBytes(id), at=System.nanoTime();
                long hits=BenchCore.run(candidate,repeats);
                long nanos=System.nanoTime()-at, bytes=bean.getThreadAllocatedBytes(id)-before;
                BenchCore.verify(hits,scenario,repeats);
                System.out.printf(Locale.ROOT,"{\"runtime\":\"desktop-jvm\",\"pair\":%d,\"variant\":\"%s\",\"calls\":%d,\"hits\":%d,\"nanos\":%d,\"allocated_bytes\":%d}%n",pair,candidate?"candidate":"baseline",repeats*8192,hits,nanos,bytes);
            }
        }
    }
}
