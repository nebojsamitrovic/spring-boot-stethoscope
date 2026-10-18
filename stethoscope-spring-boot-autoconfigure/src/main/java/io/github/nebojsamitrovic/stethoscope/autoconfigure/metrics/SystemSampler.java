package io.github.nebojsamitrovic.stethoscope.autoconfigure.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;

/**
 * Feeds this JVM's CPU and heap into the aggregator, keyed by instance name, so every instance of
 * the application shows up on its own line.
 */
public class SystemSampler {

    private static final long MB = 1024 * 1024;

    private final MetricsAggregator aggregator;
    private final String instance;
    private final Clock clock;

    public SystemSampler(MetricsAggregator aggregator, String instance, Clock clock) {
        this.aggregator = aggregator;
        this.instance = instance == null || instance.isBlank() ? hostName() : instance.trim();
        this.clock = clock;
    }

    public String instance() {
        return instance;
    }

    public void sample() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean hotspot) {
            double load = hotspot.getProcessCpuLoad();
            if (load >= 0) {
                aggregator.add(Metric.CPU, instance, Math.round(load * 1000), clock.instant(), null);
            }
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        String max = heap.getMax() < 0 ? null : String.valueOf(heap.getMax() / MB);
        aggregator.add(Metric.HEAP, instance, heap.getUsed() / MB, clock.instant(), max);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "unknown";
        }
    }
}
