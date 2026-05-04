package rental.additional.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ObservabilityService {

	private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

	private final Clock clock = Clock.systemUTC();
	private final Duration windowShort;
	private final Duration windowMedium;
	private final Duration windowLong;

	private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();

	public ObservabilityService(
			@Value("${observability.window.short}") Duration windowShort,
			@Value("${observability.window.medium}") Duration windowMedium,
			@Value("${observability.window.long}") Duration windowLong) {
		this.windowShort = windowShort;
		this.windowMedium = windowMedium;
		this.windowLong = windowLong;
	}

	private static Duration max(Duration a, Duration b, Duration c) {
		Duration m = a;
		if (b.compareTo(m) > 0) {
			m = b;
		}
		if (c.compareTo(m) > 0) {
			m = c;
		}
		return m;
	}

	public void record(String category, long durationNanos) {
		observations.add(new Observation(clock.instant(), category, durationNanos));
	}

	public <T> T timed(String category, Supplier<T> supplier) {
		long t0 = System.nanoTime();
		try {
			return supplier.get();
		}
		finally {
			record(category, System.nanoTime() - t0);
		}
	}

	public void runTimed(String category, Runnable runnable) {
		long t0 = System.nanoTime();
		try {
			runnable.run();
		}
		finally {
			record(category, System.nanoTime() - t0);
		}
	}

	@Scheduled(fixedDelayString = "${observability.log-interval}")
	public void logAggregates() {
		Instant now = clock.instant();
		Duration retention = max(windowShort, windowMedium, windowLong);
		Instant cutoff = now.minus(retention);
		observations.removeIf(o -> o.instant.isBefore(cutoff));

		emitWindow(now, windowShort, "10s");
		emitWindow(now, windowMedium, "30s");
		emitWindow(now, windowLong, "1m");
	}

	private void emitWindow(Instant now, Duration window, String label) {
		Instant from = now.minus(window);
		Map<String, long[]> agg = new HashMap<>();
		for (Observation o : observations) {
			if (o.instant.isBefore(from)) {
				continue;
			}
			long[] row = agg.computeIfAbsent(o.category, k -> new long[2]);
			row[0]++;
			row[1] += o.durationNanos;
		}
		for (Map.Entry<String, long[]> e : agg.entrySet()) {
			long count = e.getValue()[0];
			long sum = e.getValue()[1];
			long avg = count == 0 ? 0 : sum / count;
			log.info(
					"LAB9 Observability window={} category={} count={} sumNanos={} avgNanos={}",
					label,
					e.getKey(),
					count,
					sum,
					avg);
		}
	}

	private record Observation(Instant instant, String category, long durationNanos) {}
}
