import cn.song.mybatisplus.visualizer.log.SqlLogFileReader;
import cn.song.mybatisplus.visualizer.mcp.HistorySearchService;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Synthetic history benchmark. Cold means first invocation in a fresh JVM, not an OS cache flush. */
public class M19HistoryBenchmark {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mpsv-m19-history-");
        Path file = dir.resolve("sql-history-2026-10-15.sql");
        try {
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                data.append("-- source: demo/OrderService.java:10 | time: 12ms | 2026-10-15 12:00:00.000 --\n")
                    .append("SELECT id, amount FROM m19_order WHERE id = ").append(i)
                    .append(" AND status = '").append(i % 10 == 0 ? "match" : "other").append("';\n\n");
            }
            Files.writeString(file, data, StandardCharsets.UTF_8);
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
            long start = System.nanoTime();
            int count = SqlLogFileReader.INSTANCE.readFromFile(file.toFile()).size();
            long coldRead = System.nanoTime() - start;
            if (count != 10000) throw new AssertionError("Reader entry count: " + count);
            HistorySearchService.Query query = new HistorySearchService.Query("status = 'match'", null, null, null, null, true, 100);
            start = System.nanoTime();
            verify(HistorySearchService.INSTANCE.search(dir.toString(), query, System.currentTimeMillis(), 200000));
            long firstSearch = System.nanoTime() - start;
            long[] reads = new long[20];
            long[] searches = new long[20];
            for (int i = 0; i < reads.length; i++) {
                start = System.nanoTime();
                if (SqlLogFileReader.INSTANCE.readFromFile(file.toFile()).size() != 10000) throw new AssertionError();
                reads[i] = System.nanoTime() - start;
                start = System.nanoTime();
                verify(HistorySearchService.INSTANCE.search(dir.toString(), query, System.currentTimeMillis(), 200000));
                searches[i] = System.nanoTime() - start;
            }
            long heapPeak = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == java.lang.management.MemoryType.HEAP)
                .mapToLong(p -> p.getPeakUsage().getUsed()).sum();
            System.out.printf(Locale.ROOT,
                "{\"rows\":10000,\"hits\":1000,\"returned\":100,\"bytes\":%d,\"coldReadMs\":%.3f,\"firstSearchMs\":%.3f,\"warmReadP50Ms\":%.3f,\"warmReadP95Ms\":%.3f,\"warmSearchP50Ms\":%.3f,\"warmSearchP95Ms\":%.3f,\"heapPoolPeakSumBytes\":%d,\"readSamplesNs\":%s,\"searchSamplesNs\":%s}%n",
                Files.size(file), coldRead / 1e6, firstSearch / 1e6,
                percentile(reads, .5), percentile(reads, .95), percentile(searches, .5), percentile(searches, .95),
                heapPeak, Arrays.toString(reads), Arrays.toString(searches));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
    private static void verify(HistorySearchService.Result result) {
        if (result.getScannedEntries() != 10000 || result.getTotalHits() != 1000 || result.getReturned() != 100 ||
            !result.getTruncated() || result.getScanTruncated()) throw new AssertionError(result);
    }
    private static double percentile(long[] samples, double p) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(p * sorted.length) - 1] / 1e6;
    }
}
