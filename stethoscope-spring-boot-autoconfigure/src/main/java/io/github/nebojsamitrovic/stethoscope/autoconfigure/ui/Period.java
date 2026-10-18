package io.github.nebojsamitrovic.stethoscope.autoconfigure.ui;

import io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics.MetricRow;
import java.time.Duration;

/** Time range the overview shows, the bucket size it reads and how many bars its chart has. */
enum Period {

    HOUR("1h", Duration.ofHours(1), MetricRow.MINUTE, 60),
    SIX_HOURS("6h", Duration.ofHours(6), MetricRow.MINUTE, 72),
    DAY("24h", Duration.ofHours(24), MetricRow.MINUTE, 96),
    WEEK("7d", Duration.ofDays(7), MetricRow.HOUR, 84);

    private final String slug;
    private final Duration length;
    private final int bucketSeconds;
    private final int bars;

    Period(String slug, Duration length, int bucketSeconds, int bars) {
        this.slug = slug;
        this.length = length;
        this.bucketSeconds = bucketSeconds;
        this.bars = bars;
    }

    String slug() {
        return slug;
    }

    int bucketSeconds() {
        return bucketSeconds;
    }

    int bars() {
        return bars;
    }

    long barSeconds() {
        return length.toSeconds() / bars;
    }

    /** Start of the first bar, so that the last bar holds {@code now}. */
    long from(long now) {
        long bar = barSeconds();
        return now - Math.floorMod(now, bar) - (bars - 1) * bar;
    }

    static Period fromSlug(String value) {
        for (Period period : values()) {
            if (period.slug.equals(value)) {
                return period;
            }
        }
        return HOUR;
    }
}
