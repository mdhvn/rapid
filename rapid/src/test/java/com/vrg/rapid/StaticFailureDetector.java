package com.vrg.rapid;

import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.vrg.rapid.monitoring.ILinkFailureDetector;
import com.vrg.rapid.pb.ProbeMessage;
import com.vrg.rapid.pb.ProbeResponse;
import io.grpc.stub.StreamObserver;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Used for testing.
 */
public class StaticFailureDetector implements ILinkFailureDetector {
    final Set<HostAndPort> failedNodes;
    @Nullable List<HostAndPort> monitorees = null;

    public StaticFailureDetector(final Set<HostAndPort> blackList) {
        this.failedNodes = blackList;
    }

    @Override
    public ListenableFuture<Void> checkMonitoree(final HostAndPort monitoree) {
        return Futures.immediateFuture(null);
    }

    @Override
    public void handleProbeMessage(final ProbeMessage probeMessage,
                                   final StreamObserver<ProbeResponse> probeResponseStreamObserver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasFailed(final HostAndPort monitorees) {
        return failedNodes.contains(monitorees);
    }

    @Override
    public void onMembershipChange(final List<HostAndPort> monitorees) {
        this.monitorees = monitorees;
    }

    public void addFailedNodes(final Set<HostAndPort> nodes) {
        failedNodes.addAll(nodes);
    }
}