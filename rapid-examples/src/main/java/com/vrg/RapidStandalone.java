package com.vrg;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.AddressFromURIString;
import akka.actor.Props;
import akka.cluster.MemberStatus;
import akka.util.Timeout;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.vrg.rapid.NodeStatusChange;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Rapid Cluster example.
 */
public class RapidStandalone
{
    @Nullable private static Logger nettyLogger;
    @Nullable private static Logger grpcLogger;
    @Nullable private static ActorSystem actorSystem;
    @Nullable private static ActorRef localActor;
    private static final String APPLICATION = "rapid-akka";
    private static final Timeout timeout = new Timeout(Duration.create(1000, "milliseconds"));

    static {
        grpcLogger = Logger.getLogger("io.grpc");
        grpcLogger.setLevel(Level.WARNING);
        nettyLogger = Logger.getLogger("io.grpc.netty.NettyServerHandler");
        nettyLogger.setLevel(Level.OFF);
    }

    /**
     * Executed whenever a Cluster VIEW_CHANGE_PROPOSAL event occurs.
     */
    private static void onViewChangeProposal(final List<NodeStatusChange> viewChange) {
        System.out.println("The condition detector has outputted a proposal: " + viewChange);
    }

    /**
     * Executed whenever a Cluster VIEW_CHANGE_ONE_STEP_FAILED event occurs.
     */
    private static void onViewChangeOneStepFailed(final List<NodeStatusChange> viewChange) {
        System.out.println("The condition detector had a conflict during one-step consensus: " + viewChange);
    }

    /**
     * Executed whenever a Cluster KICKED event occurs.
     */
    private static void onKicked(final List<NodeStatusChange> viewChange) {
        System.out.println("We got kicked from the network: " + viewChange);
    }

    /**
     * Executed whenever a Cluster VIEW_CHANGE event occurs.
     */
    private static void onViewChange(final List<NodeStatusChange> viewChange) {
        System.out.println("View change detected: " + viewChange);
    }

    /**
     * Takes a node-change event and the associated metadata to obtain an ActorRef.
     */
    private static ActorRef getActorRefForHost(final NodeStatusChange statusChange) {
        Objects.requireNonNull(actorSystem);
        try {
            final String hostname = statusChange.getHostAndPort().getHost();    // Rapid host
            final String port = statusChange.getMetadata().get("akkaPort");     // Port for actor system
            return Await.result(actorSystem.actorSelection(
                "akka.tcp://" + APPLICATION + "1@" + hostname + ":" + port + "/user/Printer").resolveOne(timeout),
                timeout.duration());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String seedUri(final HostAndPort seedAddress) {
        return "akka.tcp://" + APPLICATION + "@" + seedAddress.getHost() + ":" + seedAddress.getPort();
    }

    public static void main( String[] args ) throws ParseException, IOException, InterruptedException {
        final Options options = new Options();
        options.addRequiredOption("cluster", "cluster", true, "Cluster tool to use");
        options.addRequiredOption("l", "listenAddresses", true, "The listening addresses Rapid Cluster instances");
        options.addRequiredOption("s", "seedAddress", true, "The seed node's address for the bootstrap protocol");
        options.addRequiredOption("r", "role", true, "The node's role for the cluster");
        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd = parser.parse(options, args);

        // Get CLI options
        final String clusterTool = cmd.getOptionValue("cluster");
        String addresses = cmd.getOptionValue("listenAddresses");
        addresses = addresses.replaceAll("\\s","");
        final List<HostAndPort> listenAddresses = Arrays.stream(addresses.split(",")).map(HostAndPort::fromString)
                                                      .collect(Collectors.toList());
        final HostAndPort seedAddress = HostAndPort.fromString(cmd.getOptionValue("seedAddress"));
        final String role = cmd.getOptionValue("role");
        final Executor executor = Executors.newWorkStealingPool(listenAddresses.size());

        if (clusterTool.equals("AkkaCluster")) {
            if (!listenAddresses.contains(seedAddress)) {
                Thread.sleep(10000);
            }
            // Initialize Actor system
            listenAddresses.forEach(listenAddress -> {
                try {
                    final Config config = ConfigFactory.parseString(
                        "akka {\n" +
                            " stdout-loglevel = \"OFF\"\n" +
                            " loglevel = \"OFF\"\n" +
                            " actor {\n" +
                            "   provider = akka.cluster.ClusterActorRefProvider\n" +
                            " }\n" +
                            " serialization-bindings {\n" +
                            "   \"java.io.Serializable\" = none\n" +
                            " }\n" +
                            " remote {\n" +
                            "   enabled-transports = [\"akka.remote.netty.tcp\"]\n" +
                            "   netty.tcp {\n" +
                            "     hostname = \"" + listenAddress.getHost() + "\"\n" +
                            "     port = " + listenAddress.getPort() + "\n" +
                            "   }\n" +
                            " }\n" +
                            "}");
                    actorSystem = ActorSystem.create(APPLICATION, config);
                    assert actorSystem != null;
                    localActor = actorSystem.actorOf(Props.create(AkkaListener.class), "Actor:" + listenAddress);
                    final akka.cluster.Cluster cluster = akka.cluster.Cluster.get(actorSystem);
                    cluster.subscribe(localActor, akka.cluster.ClusterEvent.ClusterDomainEvent.class);
                    cluster.join(AddressFromURIString.parse(seedUri(seedAddress)));

                    executor.execute(() -> {
                        int tries = 400;
                        while (tries-- > 0) {
                            final int up = ImmutableList.copyOf(cluster.state().getMembers())
                                    .stream().filter(member -> member.status().equals(MemberStatus.up()))
                                    .collect(Collectors.toList()).size();
                            final int unreachable = cluster.state().getUnreachable().size();
                            System.out.println(System.currentTimeMillis() + " " + listenAddress +
                                    " Cluster size " + (up - unreachable) + " " + tries);
                            try {
                                Thread.sleep(1000);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        System.exit(0);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        else if (clusterTool.equals("Rapid")) {
            listenAddresses.forEach(listenAddress -> executor.execute(() -> {
                // Setup Rapid cluster
                try {
                    System.out.println(listenAddress);
                    final com.vrg.rapid.Cluster cluster;
                    if (listenAddress.equals(seedAddress)) {
                            cluster = new com.vrg.rapid.Cluster.Builder(listenAddress)
                                    .start();

                    } else {
                        Thread.sleep(10000);
                        cluster = new com.vrg.rapid.Cluster.Builder(listenAddress)
                                .join(seedAddress);
                    }

                    cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE_PROPOSAL,
                            RapidStandalone::onViewChangeProposal);
                    cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE,
                            RapidStandalone::onViewChange);
                    cluster.registerSubscription(com.vrg.rapid.ClusterEvents.VIEW_CHANGE_ONE_STEP_FAILED,
                            RapidStandalone::onViewChangeOneStepFailed);
                    cluster.registerSubscription(com.vrg.rapid.ClusterEvents.KICKED,
                            RapidStandalone::onKicked);

                    int tries = 400;
                    while (tries-- > 0) {
                        System.out.println(System.currentTimeMillis() + " " + listenAddress +
                                " Cluster size " + cluster.getMemberlist().size() + " " + tries);
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                System.exit(0);
            }));
        }

        Thread.currentThread().join();
    }
}
