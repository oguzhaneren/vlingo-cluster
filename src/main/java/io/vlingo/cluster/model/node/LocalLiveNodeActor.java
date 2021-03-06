// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.cluster.model.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.vlingo.actors.Actor;
import io.vlingo.actors.Cancellable;
import io.vlingo.actors.Scheduled;
import io.vlingo.cluster.model.ClusterSnapshot;
import io.vlingo.cluster.model.Configuration;
import io.vlingo.cluster.model.Properties;
import io.vlingo.cluster.model.message.CheckHealth;
import io.vlingo.cluster.model.message.Directory;
import io.vlingo.cluster.model.message.Elect;
import io.vlingo.cluster.model.message.Join;
import io.vlingo.cluster.model.message.Leader;
import io.vlingo.cluster.model.message.Leave;
import io.vlingo.cluster.model.message.OperationalMessage;
import io.vlingo.cluster.model.message.Ping;
import io.vlingo.cluster.model.message.Pulse;
import io.vlingo.cluster.model.message.Split;
import io.vlingo.cluster.model.message.Vote;
import io.vlingo.cluster.model.outbound.OperationalOutboundStream;

public class LocalLiveNodeActor extends Actor
  implements LocalLiveNode, LiveNodeMaintainer, Scheduled {

  private final Cancellable cancellable;
  private final CheckHealth checkHealth;
  private final Configuration configuration;
  private LiveNodeState state;
  private final Node node;
  private final List<NodeSynchronizer> nodeSynchronizers;
  private final OperationalOutboundStream outbound;
  private boolean quorumAchieved;
  private final LocalLiveNode selfLocalLiveNode;
  private final ClusterSnapshot snapshot;
  private final Registry registry;

  public LocalLiveNodeActor(
          final Node node,
          final ClusterSnapshot snapshot,
          final Registry registry,
          final OperationalOutboundStream outbound,
          final Configuration configuration) {
    
    this.node = node;
    this.snapshot = snapshot;
    this.registry = registry;
    this.outbound = outbound;
    this.configuration = configuration;
    this.nodeSynchronizers = new ArrayList<>();
    this.selfLocalLiveNode = selfAs(LocalLiveNode.class);
    this.checkHealth = new CheckHealth(node.id());
    this.cancellable = scheduleHealthCheck();
    
    declareIdle();
  }


  //===================================
  // LocalLiveNode
  //===================================

  @Override
  public void handle(final OperationalMessage message) {
    if (message.isDirectory())    state.handle((Directory) message);
    else if (message.isElect())   state.handle((Elect) message);
    else if (message.isJoin())    state.handle((Join) message);
    else if (message.isLeader())  state.handle((Leader) message);
    else if (message.isLeave())   state.handle((Leave) message);
    else if (message.isPing())    state.handle((Ping) message);
    else if (message.isPulse())   state.handle((Pulse) message);
    else if (message.isSplit())   state.handle((Split) message);
    else if (message.isVote())    state.handle((Vote) message);
    else if (message.isCheckHealth()) {
      checkHealth();
      informHealth();
    }
  }

  @Override
  public void registerNodeSynchronizer(final NodeSynchronizer nodeSynchronizer) {
    nodeSynchronizers.add(nodeSynchronizer);
  }


  //================================================
  //== LiveNodeMaintainer
  //================================================

  public void assertNewLeadership(final Id assertingNodeId) {
    
    //--------------------------------------------------------------------------------------------
    // -- Handles the following kinds of conditions:
    // --
    // -- Cluster is {1,2,3} and {3} is the leader so {1} and {2} are followers. A network
    // -- partition occurs between {2} and {3} and {2} wants to take over as leader because
    // -- it thinks that {3} died, but {1} and {3} can still see each other. So how does {1}
    // -- deal with the situation where it is already in a quorum with {1,3} but {2} can’t
    // -- see node {3} so it tells {1} that it wants to be leader. Of course this can also
    // -- happen if the network partition occurs before {3} originally declares itself as
    // -- the leader, but both situations can be dealt with in the same way.
    // -- 
    // -- This is a simple solution and may need a better one.
    //--------------------------------------------------------------------------------------------

    final Node currentLeader = registry.currentLeader();

    if (currentLeader.isLeaderOver(assertingNodeId)) {
      outbound.split(assertingNodeId, currentLeader.id());
    } else {
      declareFollower();
      promoteElectedLeader(assertingNodeId);
    }
  }

  public void declareLeadership() {
    outbound.directory(new TreeSet<Node>(registry.liveNodes()));
    outbound.leader();
  }

  public void escalateElection(final Id electId) {
    if (node.id().greaterThan(electId)) {
      if (!state.leaderElectionTracker.hasStarted()) {
        state.leaderElectionTracker.start(true);
        outbound.elect(configuration.allGreaterConfiguredNodes(node.id()));
      }
      outbound.vote(electId);
    }
  }
  
  public void declareNodeSplit(final Id leaderNodeId) {
    declareFollower();
    promoteElectedLeader(leaderNodeId);
  }
  
  public void dropNode(final Id id) {
    final boolean droppedLeader = registry.isLeader(id);

    dropNodeFromCluster(id);

    if (droppedLeader) {
      state.leaderElectionTracker.start(true);
      outbound.elect(configuration.allGreaterConfiguredNodes(node.id()));
    }

    if (state.isLeader()) {
      declareLeadership();
    }
  }

  public void join(final Node joiningNode) {
    registry.join(joiningNode);
    outbound.open(joiningNode.id());
    
    if (state.isLeader()) {
      declareLeadership();
    }

    synchronize(joiningNode);
  }
  
  public void joinLocalWith(final Node remoteNode) {
    join(node);
    join(remoteNode);
  }
  
  public void mergeAllDirectoryEntries(final Set<Node> nodes) {
    registry.mergeAllDirectoryEntries(nodes);
  }
  
  public void overtakeLeadership(final Id leaderNodeId) {
    declareFollower();
  }
  
  public void placeVote(final Id voterId) {
    // should not happen that nodeId > voterId, unless
    // there is a late Join or Directory received
    if (node.id().greaterThan(voterId)) {
      outbound.vote(voterId);
    } else {
      state.leaderElectionTracker.clear();
    }
  }
  
  public void providePulseTo(final Id id) {
    outbound.pulse(id);
  }

  @Override
  public void synchronize(final Node node) {
    for (final NodeSynchronizer syncher : this.nodeSynchronizers) {
      syncher.synchronize(node);
    }
  }

  public void updateLastHealthIndication(final Id id) {
    registry.updateLastHealthIndication(id);
  }
  
  public void voteForLocalNode(final Id targetNodeId) {
    outbound.vote(targetNodeId);
    declareLeadership();
  }


  //===================================
  // Scheduled
  //===================================

  @Override
  public void intervalSignal(final Scheduled scheduled, final Object data) {
    registry.cleanTimedOutNodes();
    
    selfLocalLiveNode.handle(checkHealth);
  }


  //===================================
  // Stoppable
  //===================================

  @Override
  public void stop() {
    outbound.leave();
    cancellable.cancel();
    registry.leave(node.id());
    super.stop();
  }


  //===================================
  // internal implementation
  //===================================

  private void checkHealth() {
    if (registry.hasQuorum()) {
      maintainHealthWithQuorum();
    } else {
      maintainHealthWithNoQuorum();
    }
  }

  private void declareFollower() {
    if (state == null || !state.isFollower()) {
      System.out.println("vlingo/cluster: Cluster follower: " + node);
      
      state = new FollowerState(node, this);
    }
  }

  private void declareIdle() {
    if (state == null || !state.isIdle()) {
      System.out.println("vlingo/cluster: Cluster idle: " + node);
      
      state = new IdleState(node, this);
      
      if (registry.currentLeader().equals(node)) {
        registry.demoteLeaderOf(node.id());
      }
    }
  }

  private void declareLeader() {
    System.out.println("vlingo/cluster: Cluster leader: " + node);

    state = new LeaderState(node, this);

    promoteElectedLeader(node.id());

    outbound.directory(registry.liveNodes());

    outbound.leader();
  }

  private void dropNodeFromCluster(final Id nodeId) {
    if (registry.hasMember(nodeId)) {
      registry.leave(nodeId);
      outbound.close(nodeId);
    }
  }

  private void informHealth() {
    outbound.pulse();

    if (registry.hasMember(node.id())) {
      registry.updateLastHealthIndication(node.id());
    }

    if (state.isIdle() || !registry.isConfirmedByLeader(node.id())) {
      outbound.join();
    }
  }

  private void maintainHealthWithNoQuorum() {
    state.leaderElectionTracker.reset();

    state.noQuorumTracker.start();

    watchForQuorumRelinquished();

    if (state.noQuorumTracker.hasTimedOut()) {
      System.out.println("vlingo/cluster: No quorum; leaving cluster to become idle node.");
      registry.leave(node.id());
      declareIdle();
    }
  }

  private void maintainHealthWithQuorum() {
    state.noQuorumTracker.reset();

    watchForQuorumAchievement();

    if (!registry.hasLeader()) {
      if (!state.leaderElectionTracker.hasStarted()) {
        state.leaderElectionTracker.start();
        outbound.elect(configuration.allGreaterConfiguredNodes(node.id()));
      } else if (state.leaderElectionTracker.hasTimedOut()) {
        declareLeader();
      }
    }
  }

  private void promoteElectedLeader(final Id leaderNodeId) {
    if (node.id().equals(leaderNodeId)) {
      
      // I've seen the leader get bumped out of its own
      // registry during a weird network partition or
      // something and it can never get back leadership
      // or even rejoin the cluster because it's missing
      // from the local registry
      registry.join(node);
      
      registry.declareLeaderAs(leaderNodeId);
      
      registry.confirmAllLiveNodesByLeader();
      
    } else {
      
      if (registry.isLeader(node.id())) {
        registry.demoteLeaderOf(node.id());
      }
      
      if (!registry.hasMember(leaderNodeId)) {
        registry.join(configuration.configuredNodeMatching(leaderNodeId));
      }
      
      registry.declareLeaderAs(leaderNodeId);
    }
  }

  private Cancellable scheduleHealthCheck() {
    return
      stage()
      .world()
      .scheduler()
      .schedule(
              selfAs(Scheduled.class),
              null,
              1000L,
              Properties.instance.clusterHealthCheckInterval());
  }

  private void watchForQuorumAchievement() {
    if (!quorumAchieved) {
      quorumAchieved = true;
      snapshot.quorumAchieved();
    }
  }

  private void watchForQuorumRelinquished() {
    if (quorumAchieved) {
      quorumAchieved = false;
      snapshot.quorumLost();
    }
  }
}
