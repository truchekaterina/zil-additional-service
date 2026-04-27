package rental.additional.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    private static final int MAX_EVENTS = 500_000;

    private final ObservabilityProperties properties;
    private final ArrayDeque<Observation> events = new ArrayDeque<>();
    private final Object lock = new Object();

    public ObservabilityService(ObservabilityProperties properties) {
        this.properties = properties;
    }

    public void record(String category, long durationNanos) {
        Instant end = Instant.now();
        synchronized (lock) {
            events.addLast(new Observation(end, durationNanos, category));
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
            pruneLocked(Instant.now());
        }
    }

    private void pruneLocked(Instant now) {
        List<Duration> windows = properties.parsedWindows();
        Duration max = windows.stream().max(Comparator.naturalOrder()).orElse(Duration.ofMinutes(1));
        Instant cutoff = now.minus(max);
        while (!events.isEmpty() && events.peekFirst().endTime().isBefore(cutoff)) {
            events.removeFirst();
        }
    }

    @Scheduled(fixedRateString = "${observability.tick-millis:5000}")
    public void emitSnapshots() {
        List<Duration> windows = properties.parsedWindows();
        List<Observation> snapshot;
        synchronized (lock) {
            Instant now = Instant.now();
            pruneLocked(now);
            snapshot = new ArrayList<>(events);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder(512);
        sb.append("OBS snapshot");
        for (Duration w : windows) {
            Instant from = now.minus(w);
            Map<String, Agg> byCat = aggregate(snapshot, from);
            if (byCat.isEmpty()) {
                continue;
            }
            sb.append(" | ").append(w).append(": ");
            String part = byCat.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "{n=" + e.getValue().count + ",avgMs="
                            + formatMs(e.getValue().avgNanos() / 1_000_000.0) + "}")
                    .collect(Collectors.joining(","));
            sb.append(part);
        }
        String line = sb.toString();
        if (line.indexOf('|') < 0) {
            return;
        }
        log.info(line);
    }

    private static Map<String, Agg> aggregate(List<Observation> snapshot, Instant from) {
        Map<String, Agg> map = new HashMap<>();
        for (Observation o : snapshot) {
            if (!o.endTime().isBefore(from)) {
                map.computeIfAbsent(o.category(), k -> new Agg()).add(o.durationNanos());
            }
        }
        return map;
    }

    private static String formatMs(double ms) {
        return String.format(java.util.Locale.ROOT, "%.3f", ms);
    }

    private record Observation(Instant endTime, long durationNanos, String category) {
    }

    private static final class Agg {
        private long count;
        private long sumNanos;

        void add(long nanos) {
            count++;
            sumNanos += nanos;
        }

        double avgNanos() {
            return count == 0 ? 0.0 : sumNanos / (double) count;
        }
    }
}
