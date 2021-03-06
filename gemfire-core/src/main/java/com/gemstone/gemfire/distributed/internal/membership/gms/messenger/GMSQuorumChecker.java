package com.gemstone.gemfire.distributed.internal.membership.gms.messenger;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.Logger;
import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.Receiver;
import org.jgroups.View;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.NetView;
import com.gemstone.gemfire.distributed.internal.membership.QuorumChecker;
import com.gemstone.gemfire.internal.concurrent.ConcurrentHashSet;
import com.gemstone.gemfire.internal.logging.LogService;

public class GMSQuorumChecker implements QuorumChecker {
  private static final Logger logger = LogService.getLogger();
  private boolean isDebugEnabled = false;
  private Map<SocketAddress, InternalDistributedMember> addressConversionMap;
  private GMSPingPonger pingPonger;

  private Set<InternalDistributedMember> receivedAcks;

  private NetView lastView;

  // guarded by this
  private boolean quorumAchieved = false;
  private JChannel channel;
  private JGAddress myAddress;
  private int partitionThreshold;

  public GMSQuorumChecker(NetView jgView, int partitionThreshold, JChannel channel) {
    this.lastView = jgView;
    this.partitionThreshold = partitionThreshold;
    this.channel = channel;
  }

  public void initialize() {
    receivedAcks = new ConcurrentHashSet<InternalDistributedMember>();

    pingPonger = new GMSPingPonger();
//    UUID logicalAddress = (UUID) channel.getAddress();
//    IpAddress ipaddr = (IpAddress) channel.down(new Event(Event.GET_PHYSICAL_ADDRESS));
//    
//    myAddress = new JGAddress(logicalAddress, ipaddr);
    myAddress = (JGAddress)channel.down(new Event(Event.GET_LOCAL_ADDRESS));

    addressConversionMap = new ConcurrentHashMap<SocketAddress, InternalDistributedMember>(this.lastView.size());
    List<InternalDistributedMember> members = this.lastView.getMembers();
    for (InternalDistributedMember addr : members) {
      SocketAddress sockaddr = new InetSocketAddress(addr.getNetMember().getInetAddress(), addr.getPort());
      addressConversionMap.put(sockaddr, addr);
    }

    isDebugEnabled = logger.isDebugEnabled();
    resume();
  }

  @Override
  public synchronized boolean checkForQuorum(long timeout) throws InterruptedException {
    if (quorumAchieved) {
      return true;
    }

    if (isDebugEnabled) {
      logger.debug("beginning quorum check with {}", this);
    }
    try {
      sendPingMessages();
      quorumAchieved = waitForResponses(lastView.getMembers().size(), timeout);
      // If we did not achieve full quorum, calculate if we achieved quorum
      if (!quorumAchieved) {
        quorumAchieved = calculateQuorum();
      }
    } finally {

    }
    return quorumAchieved;
  }

  @Override
  public void suspend() {
    // NO-OP for this implementation
  }
  
  @Override
  public void close() {
    if (channel != null  &&  !channel.isClosed()) {
      channel.close();
    }
  }

  @Override
  public void resume() {
    channel.setReceiver(null);
    channel.setReceiver(new QuorumCheckerReceiver());
  }
  
  @Override
  public NetView getView() {
    return this.lastView;
  }

  @Override
  public Object getMembershipInfo() {
    return channel;
  }

  private boolean calculateQuorum() {
    // quorum check
    int weight = getWeight(this.lastView.getMembers(), this.lastView.getLeadMember());
    int ackedWeight = getWeight(receivedAcks, this.lastView.getLeadMember());
    int lossThreshold = (int) Math.round((weight * this.partitionThreshold) / 100.0);
    if (isDebugEnabled) {
      logger.debug("quorum check: contacted {} processes with {} member weight units.  Threshold for a quorum is {}", receivedAcks.size(), ackedWeight, lossThreshold);
    }
    return (ackedWeight >= lossThreshold);
  }

  private boolean waitForResponses(int numMembers, long timeout) throws InterruptedException {
    long endTime = System.currentTimeMillis() + timeout;
    for (;;) {
      long time = System.currentTimeMillis();
      long remaining = (endTime - time);
      if (remaining <= 0) {
        if (isDebugEnabled) {
          logger.debug("quorum check: timeout waiting for responses.  {} responses received", receivedAcks.size());
        }
        break;
      }
      if (isDebugEnabled) {
        logger.debug("quorum check: waiting up to {}ms to receive a quorum of responses", remaining);
      }
      Thread.sleep(500);
      if (receivedAcks.size() == numMembers) {
        // we've heard from everyone now so we've got a quorum
        if (isDebugEnabled) {
          logger.debug("quorum check: received responses from all members that were in the old distributed system");
        }
        return true;
      }
    }
    return false;
  }

  private int getWeight(Collection<InternalDistributedMember> idms, InternalDistributedMember leader) {
    int weight = 0;
    for (InternalDistributedMember mbr : idms) {
      int thisWeight = mbr.getNetMember().getMemberWeight();
      if (mbr.getVmKind() == 10 /* NORMAL_DM_KIND */) {
        thisWeight += 10;
        if (leader != null && mbr.equals(leader)) {
          thisWeight += 5;
        }
      } else if (mbr.getNetMember().preferredForCoordinator()) {
        thisWeight += 3;
      }
      weight += thisWeight;
    }
    return weight;
  }

  private void sendPingMessages() {
    // send a ping message to each member in the last view seen
    List<InternalDistributedMember> members = this.lastView.getMembers();
    for (InternalDistributedMember addr : members) {
      if (!receivedAcks.contains(addr)) {
        JGAddress dest = new JGAddress(addr);
        if (isDebugEnabled) {
          logger.debug("quorum check: sending request to {}", addr);
        }
        try {
          pingPonger.sendPingMessage(channel, myAddress, dest);
        } catch (Exception e) {
          logger.debug("Failed sending Ping message to " + dest);
        }
      }
    }
  }

  private class QuorumCheckerReceiver implements Receiver {

    @Override
    public void receive(Message msg) {
      Object contents = msg.getBuffer();
      if (contents instanceof byte[]) {
        byte[] msgBytes = (byte[]) contents;
        if (pingPonger.isPingMessage(msgBytes)) {
          try {
            pingPonger.sendPongMessage(channel, myAddress, msg.getSrc());
          } catch (Exception e) {
            logger.debug("Failed sending Pong message to " + msg.getSrc());
          }
        } else if (pingPonger.isPongMessage(msgBytes)) {
          pongReceived(msg.getSrc());
        }
      }
    }

    @Override
    public void getState(OutputStream output) throws Exception {
      // TODO Auto-generated method stub
    }

    @Override
    public void setState(InputStream input) throws Exception {
      // TODO Auto-generated method stub
    }

    @Override
    public void viewAccepted(View new_view) {
      // TODO Auto-generated method stub
    }

    @Override
    public void suspect(Address suspected_mbr) {
      // TODO Auto-generated method stub
    }

    @Override
    public void block() {
      // TODO Auto-generated method stub
    }

    @Override
    public void unblock() {
      // TODO Auto-generated method stub
    }

    public void pongReceived(Address sender) {
      logger.debug("received ping-pong response from {}", sender);
      JGAddress jgSender = (JGAddress) sender;
      SocketAddress sockaddr = new InetSocketAddress(jgSender.getInetAddress(), jgSender.getPort());
      InternalDistributedMember memberAddr = addressConversionMap.get(sockaddr);

      if (memberAddr != null) {
        logger.debug("quorum check: mapped address to member ID {}", memberAddr);
        receivedAcks.add(memberAddr);
      }
    }
  }
  
  public String toString() {
    return getClass().getSimpleName() + " on view " + this.lastView;
  }

}
