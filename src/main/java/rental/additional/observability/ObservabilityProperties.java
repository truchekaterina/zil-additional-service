package rental.additional.observability;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private String windows = "10s,30s,1m";
    private long tickMillis = 5000L;

    public String getWindows() {
        return windows;
    }

    public void setWindows(String windows) {
        this.windows = windows;
    }

    public long getTickMillis() {
        return tickMillis;
    }

    public void setTickMillis(long tickMillis) {
        this.tickMillis = tickMillis;
    }

    public List<Duration> parsedWindows() {
        List<Duration> out = new ArrayList<>();
        for (String part : windows.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(parseWindowToken(p));
            }
        }
        if (out.isEmpty()) {
            out.add(Duration.ofSeconds(10));
            out.add(Duration.ofSeconds(30));
            out.add(Duration.ofMinutes(1));
        }
        return out;
    }

    private static Duration parseWindowToken(String raw) {
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("pt")) {
            return Duration.parse(s);
        }
        if (lower.endsWith("ms")) {
            long ms = Long.parseLong(s.substring(0, s.length() - 2).trim());
            return Duration.ofMillis(ms);
        }
        if (lower.endsWith("m") && !lower.endsWith("ms")) {
            long minutes = Long.parseLong(s.substring(0, s.length() - 1).trim());
            return Duration.ofMinutes(minutes);
        }
        if (lower.endsWith("s")) {
            long seconds = Long.parseLong(s.substring(0, s.length() - 1).trim());
            return Duration.ofSeconds(seconds);
        }
        throw new IllegalArgumentException("unsupported observability window: " + raw);
    }
}
