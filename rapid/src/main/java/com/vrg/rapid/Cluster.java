/*
 * Copyright © 2016 - 2017 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an “AS IS” BASIS, without warranties or conditions of any kind,
 * EITHER EXPRESS OR IMPLIED. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.vrg.rapid;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.vrg.rapid.monitoring.ILinkFailureDetectorFactory;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.JoinStatusCode;
import com.vrg.rapid.pb.Metadata;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.Internal;
import io.grpc.ServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The public API for Rapid. Users create Cluster objects using either Cluster.start()
 * or Cluster.join(), depending on whether the user is starting a new cluster or not:
 *
 * <pre>
 * {@code
 *   HostAndPort seedAddress = HostAndPort.fromString("127.0.0.1", 1234);
 *   Cluster c = Cluster.Builder(seedAddress).start();
 *   ...
 *   HostAndPort joinerAddress = HostAndPort.fromString("127.0.0.1", 1235);
 *   Cluster c = Cluster.Builder(joinerAddress).join(seedAddress);
 * }
 * </pre>
 *
 * The API does not yet support a node, as identified by a hostname and port, to
 * be part of multiple separate clusters.
 */
public final class Cluster {
    private static final Logger LOG = LoggerFactory.getLogger(Cluster.class);
    private static final int K = 10;
    private static final int H = 9;
    private static final int L = 4;
    private static final int RETRIES = 5;
    private final MembershipService membershipService;
    private final RpcServer rpcServer;
    private final ExecutorService protocolExecutor;
    private final HostAndPort listenAddress;

    private Cluster(final RpcServer rpcServer,
                    final MembershipService membershipService,
                    final ExecutorService executorService,
                    final HostAndPort listenAddress) {
        this.membershipService = membershipService;
        this.rpcServer = rpcServer;
        this.protocolExecutor = executorService;
        this.listenAddress = listenAddress;
    }

    public static class Builder {
        private final HostAndPort listenAddress;
        @Nullable private ILinkFailureDetectorFactory linkFailureDetectorFactory = null;
        private Metadata metadata = Metadata.getDefaultInstance();
        private List<ServerInterceptor> serverInterceptors = Collections.emptyList();
        private List<ClientInterceptor> clientInterceptors = Collections.emptyList();

        /**
         * Instantiates a builder for a Rapid Cluster node that will listen on the given {@code listenAddress}
         *
         * @param listenAddress The listen address of the node being instantiated
         */
        public Builder(final HostAndPort listenAddress) {
            this.listenAddress = listenAddress;
        }

        /**
         * Set static application-specific metadata that is associated with the node being instantiated.
         * This may include tags like "role":"frontend".
         *
         * @param metadata A map specifying a set of key-value tags.
         */
        @ExperimentalApi
        public Builder setMetadata(final Map<String, String> metadata) {
            Objects.requireNonNull(metadata);
            this.metadata = Metadata.newBuilder().putAllMetadata(metadata).build();
            return this;
        }

        /**
         * Set a link failure detector to use for monitors to watch their monitorees.
         *
         * @param linkFailureDetectorFactory Returns link failure detectors used as input for Rapid's failure detection.
         */
        @ExperimentalApi
        public Builder setLinkFailureDetector(final ILinkFailureDetectorFactory linkFailureDetectorFactory) {
            Objects.requireNonNull(linkFailureDetectorFactory);
            this.linkFailureDetectorFactory = linkFailureDetectorFactory;
            return this;
        }

        /**
         * This is used by tests to inject message drop interceptors at the RpcServer.
         */
        @Internal
        Builder setServerInterceptors(final List<ServerInterceptor> interceptors) {
            this.serverInterceptors = interceptors;
            return this;
        }

        /**
         * This is used by tests to inject message drop interceptors at the client channels.
         */
        @Internal
        Builder setClientInterceptors(final List<ClientInterceptor> interceptors) {
            this.clientInterceptors = interceptors;
            return this;
        }

        /**
         * Joins an existing cluster, using {@code seedAddress} to bootstrap.
         *
         * @param seedAddress Seed node for the bootstrap protocol
         * @throws IOException Thrown if we cannot successfully start a server
         */
        public Cluster join(final HostAndPort seedAddress) throws IOException, InterruptedException {
            return joinCluster(seedAddress, this.listenAddress, this.linkFailureDetectorFactory, this.metadata,
                               this.serverInterceptors, this.clientInterceptors);
        }

        /**
         * Start a cluster without joining. Required to bootstrap a seed node.
         *
         * @throws IOException Thrown if we cannot successfully start a server
         */
        public Cluster start() throws IOException {
            return startCluster(this.listenAddress, this.linkFailureDetectorFactory, this.metadata, this.serverInterceptors);
        }
    }

    private static Cluster joinCluster(final HostAndPort seedAddress, final HostAndPort listenAddress,
               @Nullable final ILinkFailureDetectorFactory linkFailureDetectorFactory, final Metadata metadata,
               final List<ServerInterceptor> serverInterceptors, final List<ClientInterceptor> clientInterceptors)
                                                                            throws IOException, InterruptedException {
        UUID currentIdentifier = UUID.randomUUID();

        // This is the single-threaded protocolExecutor that handles all the protocol messaging.
        final ExecutorService protocolExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
            .setNameFormat("protocol-" + listenAddress + "-%d")
            .setUncaughtExceptionHandler(
                    (t, e) -> System.err.println(String.format("Server protocolExecutor caught exception: %s %s", t, t))
            ).build());

        final RpcServer server = new RpcServer(listenAddress, protocolExecutor);
        final RpcClient joinerClient = new RpcClient(listenAddress, clientInterceptors);
        server.startServer(serverInterceptors);
        boolean didPreviousJoinSucceed = false;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            joinerClient.createLongLivedConnections(ImmutableSet.of(seedAddress));
            // First, get the configuration ID and the monitors to contact from the seed node.
            final JoinResponse joinPhaseOneResult;
            try {
                joinPhaseOneResult = joinerClient.sendJoinMessage(seedAddress,
                        listenAddress, currentIdentifier).get();
            } catch (final ExecutionException e) {
                LOG.error("Join message to seed {} returned an exception: {}", seedAddress, e.getCause());
                continue;
            }
            assert joinPhaseOneResult != null;

            switch (joinPhaseOneResult.getStatusCode()) {
                case CONFIG_CHANGED:
                case UUID_ALREADY_IN_RING:
                    currentIdentifier = UUID.randomUUID();
                    continue;
                case MEMBERSHIP_REJECTED:
                    LOG.error("Membership rejected by {}. Quitting.", joinPhaseOneResult.getSender());
                    throw new JoinException("Membership rejected");
                case HOSTNAME_ALREADY_IN_RING:
                    /*
                     * This is a special case. If the joinPhase2 request times out before the join confirmation
                     * arrives from a monitor, a client may re-try a join by contacting the seed and get this response.
                     * It should simply get the configuration streamed to it. To do that, that client retries the
                     * join protocol but with a configuration id of -1.
                     */
                    LOG.error("Hostname already in configuration {}", joinPhaseOneResult.getConfigurationId());
                    didPreviousJoinSucceed = true;
                    break;
                case SAFE_TO_JOIN:
                    break;
                default:
                    throw new JoinException("Unrecognized status code");
            }

            // -1 if we got a HOSTNAME_ALREADY_IN_RING status code in phase 1. This means we only need to ask
            // monitors to stream us a configuration.
            final long configurationToJoin = didPreviousJoinSucceed ? -1 : joinPhaseOneResult.getConfigurationId();
            if (attempt > 0) {
                LOG.info("{} is retrying a join under a new configuration {}",
                        listenAddress, configurationToJoin);
            }

            // We have the list of monitors. Now contact them as part of phase 2.
            final List<HostAndPort> monitorList = joinPhaseOneResult.getHostsList().stream()
                    .map(HostAndPort::fromString)
                    .collect(Collectors.toList());
            final Map<HostAndPort, List<Integer>> ringNumbersPerMonitor = new HashMap<>(K);

            // Batch together requests to the same node.
            int ringNumber = 0;
            for (final HostAndPort monitor: monitorList) {
                ringNumbersPerMonitor.computeIfAbsent(monitor, (k) -> new ArrayList<>()).add(ringNumber);
                ringNumber++;
            }

            final List<ListenableFuture<JoinResponse>> responseFutures = new ArrayList<>();
            for (final Map.Entry<HostAndPort, List<Integer>> entry: ringNumbersPerMonitor.entrySet()) {
                final JoinMessage msg = JoinMessage.newBuilder()
                                                   .setSender(listenAddress.toString())
                                                   .setUuid(currentIdentifier.toString())
                                                   .setMetadata(metadata)
                                                   .setConfigurationId(configurationToJoin)
                                                   .addAllRingNumber(entry.getValue()).build();
                LOG.trace("{} is sending a join-p2 to {} for configuration {}",
                        listenAddress, entry.getKey(), configurationToJoin);
                final ListenableFuture<JoinResponse> call = joinerClient.sendJoinPhase2Message(entry.getKey(), msg);
                responseFutures.add(call);
            }

            // The returned list of responses must contain the full configuration (hosts and identifiers) we just
            // joined. Else, there's an error and we throw an exception.
            try {
                // Unsuccessful responses will be null.
                final List<JoinResponse> responses = Futures.successfulAsList(responseFutures).get();

                for (final JoinResponse response: responses) {
                    if (response == null) {
                        LOG.info("Received a null response.");
                        continue;
                    }
                    if (response.getStatusCode() == JoinStatusCode.MEMBERSHIP_REJECTED) {
                        LOG.info("Membership rejected by {}. Quitting.", response.getSender());
                        continue;
                    }

                    if (response.getStatusCode() == JoinStatusCode.SAFE_TO_JOIN
                            && response.getConfigurationId() != configurationToJoin) {
                        // Safe to proceed. Extract the list of hosts and identifiers from the message,
                        // assemble a MembershipService object and start an RpcServer.
                        final List<HostAndPort> allHosts = response.getHostsList().stream()
                                .map(HostAndPort::fromString)
                                .collect(Collectors.toList());
                        final List<UUID> identifiersSeen = response.getIdentifiersList().stream()
                                .map(UUID::fromString)
                                .collect(Collectors.toList());

                        final Map<String, Metadata> allMetadata = response.getClusterMetadataMap();

                        assert identifiersSeen.size() > 0;
                        assert allHosts.size() > 0;

                        final MembershipView membershipViewFinal =
                                new MembershipView(K, identifiersSeen, allHosts);
                        final WatermarkBuffer watermarkBuffer = new WatermarkBuffer(K, H, L);
                        MembershipService.Builder msBuilder =
                                new MembershipService.Builder(listenAddress, watermarkBuffer, membershipViewFinal,
                                                              protocolExecutor);
                        if (linkFailureDetectorFactory != null) {
                            msBuilder = msBuilder.setLinkFailureDetector(linkFailureDetectorFactory);
                        }
                        final MembershipService membershipService = msBuilder.setRpcClient(joinerClient)
                                                                             .setMetadata(allMetadata)
                                                                             .build();
                        server.setMembershipService(membershipService);
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("{} has monitors {}", listenAddress,
                                    membershipViewFinal.getMonitorsOf(listenAddress));
                            LOG.trace("{} has monitorees {}", listenAddress,
                                    membershipViewFinal.getMonitorsOf(listenAddress));
                        }
                        return new Cluster(server, membershipService, protocolExecutor, listenAddress);
                    }
                }
            } catch (final ExecutionException e) {
                LOG.error("JoinPhaseTwo request by {} for configuration {} threw an exception. Retrying. {}",
                        listenAddress, configurationToJoin, e.getMessage());
            }
        }
        server.stopServer();
        throw new JoinException("Join attempt unsuccessful " + listenAddress);
    }

    /**
     * Start a cluster without joining. Required to bootstrap a seed node.
     *
     * @param listenAddress Address to bind to after successful bootstrap
     * @param linkFailureDetectorFactory a list of checks to perform before a monitor processes a join phase two message
     * @throws IOException Thrown if we cannot successfully start a server
     */
    @VisibleForTesting
    static Cluster startCluster(final HostAndPort listenAddress,
                                @Nullable final ILinkFailureDetectorFactory linkFailureDetectorFactory,
                                final Metadata metadata,
                                final List<ServerInterceptor> interceptors) throws IOException {
        Objects.requireNonNull(listenAddress);

        // This is the single-threaded protocolExecutor that handles all the protocol messaging.
        final ExecutorService protocolExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
            .setNameFormat("protocol-" + listenAddress + "-%d")
            .setUncaughtExceptionHandler(
                    (t, e) -> System.err.println(String.format("Server protocolExecutor caught exception: %s %s", t, t))
            ).build());
        final RpcServer rpcServer = new RpcServer(listenAddress, protocolExecutor);
        final UUID currentIdentifier = UUID.randomUUID();
        final MembershipView membershipView = new MembershipView(K, Collections.singletonList(currentIdentifier),
                                                                 Collections.singletonList(listenAddress));
        final WatermarkBuffer watermarkBuffer = new WatermarkBuffer(K, H, L);
        MembershipService.Builder builder = new MembershipService.Builder(listenAddress, watermarkBuffer,
                                                                          membershipView, protocolExecutor);
        // If linkFailureDetector == null, the system defaults to using the PingPongFailureDetector
        if (linkFailureDetectorFactory != null) {
            builder = builder.setLinkFailureDetector(linkFailureDetectorFactory);
        }
        final MembershipService membershipService = metadata.getMetadataCount() > 0
                            ? builder.setMetadata(Collections.singletonMap(listenAddress.toString(), metadata)).build()
                            : builder.build();
        rpcServer.setMembershipService(membershipService);
        rpcServer.startServer(interceptors);
        return new Cluster(rpcServer, membershipService, protocolExecutor, listenAddress);
    }

    /**
     * Returns the list of hosts currently in the membership set.
     *
     * @return list of hosts in the membership set
     */
    public List<HostAndPort> getMemberlist() {
        return membershipService.getMembershipView();
    }

    /**
     * Returns the number of hosts currently in the membership set.
     *
     * @return the number of hosts in the membership set
     */
    public int getMembershipSize() {
        return membershipService.getMembershipSize();
    }

    /**
     * Returns the list of hosts currently in the membership set.
     *
     * @return list of hosts in the membership set
     */
    public Map<String, Metadata> getClusterMetadata() {
        return membershipService.getMetadata();
    }

    /**
     * Register callbacks for cluster events.
     *
     * @param event Cluster event to subscribe to
     * @param callback Callback to be executed when {@code event} occurs.
     */
    public void registerSubscription(final ClusterEvents event,
                                     final Consumer<List<NodeStatusChange>> callback) {
        membershipService.registerSubscription(event, callback);
    }

    /**
     * Shutdown the RpcServer
     */
    public void shutdown() throws InterruptedException {
        // TODO: this should probably be a "leave" method
        LOG.debug("Shutting down RpcServer and MembershipService");
        rpcServer.stopServer();
        protocolExecutor.shutdownNow();
        membershipService.shutdown();
    }

    @Override
    public String toString() {
        return "Cluster:" + listenAddress;
    }

    public static final class JoinException extends RuntimeException {
        JoinException(final String msg) {
            super(msg);
        }
    }
}
