package io.github.nebojsamitrovic.stethoscope.autoconfigure.jdbc;

import io.github.nebojsamitrovic.stethoscope.core.Recorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

/**
 * datasource-proxy listener that hands every executed statement to the {@link Recorder}.
 *
 * <p>For batched statements each parameter set is recorded as its own entry, so N inserts in a JDBC
 * batch show up as N rows. Batch statistics (query count, time) are split evenly between them.
 */
public class StethoscopeQueryListener implements QueryExecutionListener {

    private final Supplier<Recorder> recorder;

    /** Takes a supplier so the recorder is looked up lazily, after the DataSource has been wrapped. */
    public StethoscopeQueryListener(Supplier<Recorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // nothing to do before execution
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        Recorder target = recorder.get();
        if (target == null || !target.isRecording() || queryInfoList == null || queryInfoList.isEmpty()) {
            return;
        }

        long elapsedNanos = execInfo.getElapsedTime() * 1_000_000L;
        int executions = 0;
        for (QueryInfo query : queryInfoList) {
            executions += Math.max(1, query.getParametersList().size());
        }
        long perExecutionNanos = elapsedNanos / Math.max(1, executions);

        for (QueryInfo query : queryInfoList) {
            List<List<ParameterSetOperation>> parameterSets = query.getParametersList();
            if (parameterSets.isEmpty()) {
                target.recordQuery(query.getQuery(), List.of(), perExecutionNanos, execInfo.isSuccess(),
                        execInfo.getDataSourceName());
                continue;
            }
            for (List<ParameterSetOperation> parameterSet : parameterSets) {
                target.recordQuery(query.getQuery(), parameterValues(parameterSet), perExecutionNanos,
                        execInfo.isSuccess(), execInfo.getDataSourceName());
            }
        }
    }

    /** Orders parameters by index (or name for callable statements) and extracts their values. */
    static List<Object> parameterValues(List<ParameterSetOperation> operations) {
        List<ParameterSetOperation> sorted = new ArrayList<>(operations);
        sorted.sort(Comparator.comparing(StethoscopeQueryListener::sortKey));
        List<Object> values = new ArrayList<>(sorted.size());
        for (ParameterSetOperation operation : sorted) {
            Object[] args = operation.getArgs();
            if (operation.getMethod() != null && "setNull".equals(operation.getMethod().getName())) {
                values.add(null);
            } else {
                values.add(args != null && args.length > 1 ? args[1] : null);
            }
        }
        return values;
    }

    private static String sortKey(ParameterSetOperation operation) {
        Object[] args = operation.getArgs();
        Object key = args != null && args.length > 0 ? args[0] : null;
        if (key instanceof Number number) {
            // zero-pad so "10" sorts after "9"
            return String.format("%010d", number.intValue());
        }
        return String.valueOf(key);
    }
}
