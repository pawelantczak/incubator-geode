package com.gemstone.gemfire.distributed.internal.membership.gms.membership.fd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.NetView;
import com.gemstone.gemfire.distributed.internal.membership.gms.ServiceConfig;
import com.gemstone.gemfire.distributed.internal.membership.gms.Services;
import com.gemstone.gemfire.distributed.internal.membership.gms.Services.Stopper;
import com.gemstone.gemfire.distributed.internal.membership.gms.fd.GMSHealthMonitor;
import com.gemstone.gemfire.distributed.internal.membership.gms.interfaces.JoinLeave;
import com.gemstone.gemfire.distributed.internal.membership.gms.interfaces.Manager;
import com.gemstone.gemfire.distributed.internal.membership.gms.interfaces.Messenger;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.HeartbeatMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.HeartbeatRequestMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.SuspectMembersMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.SuspectRequest;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.test.junit.categories.UnitTest;

@Category(UnitTest.class)
public class GMSHealthMonitorJUnitTest {

  private Services services;
  private ServiceConfig mockConfig;
  private DistributionConfig mockDistConfig;
  private List<InternalDistributedMember> mockMembers;
  private Messenger messenger;
  private JoinLeave joinLeave;
  private GMSHealthMonitor gmsHealthMonitor;
  private Manager manager;
  final long memberTimeout = 1000l;
  private int[] portRange= new int[]{0, 65535};

  @Before
  public void initMocks() throws UnknownHostException {
    System.setProperty("gemfire.bind-address", "localhost");
    mockDistConfig = mock(DistributionConfig.class);
    mockConfig = mock(ServiceConfig.class);
    messenger = mock(Messenger.class);
    joinLeave = mock(JoinLeave.class);
    manager = mock(Manager.class);
    services = mock(Services.class);
    Stopper stopper = mock(Stopper.class);

    when(mockConfig.getDistributionConfig()).thenReturn(mockDistConfig);
    when(mockConfig.getMemberTimeout()).thenReturn(memberTimeout);
    when(mockConfig.getMembershipPortRange()).thenReturn(portRange);
    when(services.getConfig()).thenReturn(mockConfig);
    when(services.getMessenger()).thenReturn(messenger);
    when(services.getJoinLeave()).thenReturn(joinLeave);
    when(services.getCancelCriterion()).thenReturn(stopper);
    when(services.getManager()).thenReturn(manager);
    when(stopper.isCancelInProgress()).thenReturn(false);
    

    if (mockMembers == null) {
      mockMembers = new ArrayList<InternalDistributedMember>();
      for (int i = 0; i < 7; i++) {
        InternalDistributedMember mbr = new InternalDistributedMember("localhost", 8888 + i);
  
        if (i == 0 || i == 1) {
          mbr.setVmKind(DistributionManager.LOCATOR_DM_TYPE);
          mbr.getNetMember().setPreferredForCoordinator(true);
        }
        mockMembers.add(mbr);
      }
    }
    when(joinLeave.getMemberID()).thenReturn(mockMembers.get(3));
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));
    gmsHealthMonitor = new GMSHealthMonitor();
    gmsHealthMonitor.init(services);
    gmsHealthMonitor.start();
  }

  @After
  public void tearDown() {
    gmsHealthMonitor.stop();
    System.getProperties().remove("gemfire.bind-address");
  }

  @Test
  public void testHMServiceStarted() throws IOException {

    InternalDistributedMember mbr = new InternalDistributedMember(SocketCreator.getLocalHost(), 12345);
    mbr.setVmViewId(1);
    when(messenger.getMemberID()).thenReturn(mbr);
    gmsHealthMonitor.started();
    
    NetView v = new NetView(mbr, 1, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    gmsHealthMonitor.processMessage(new HeartbeatRequestMessage(mbr, 1));
    verify(messenger, atLeastOnce()).send(any(HeartbeatMessage.class));
  }

  /**
   * checks who is next neighbor
   */
  @Test
  public void testHMNextNeighborVerify() throws IOException {

    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);

    Assert.assertEquals(mockMembers.get(4), gmsHealthMonitor.getNextNeighbor());

  }

  @Test
  public void testHMNextNeighborAfterTimeout() throws Exception {
    System.out.println("testHMNextNeighborAfterTimeout starting");
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

//    System.out.printf("memberID is %s view is %s\n", mockMembers.get(3), v);
    
    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);

    // allow the monitor to give up on the initial "next neighbor" and
    // move on to the one after it
    long giveup = System.currentTimeMillis() + memberTimeout + 500;
    InternalDistributedMember expected = mockMembers.get(5);
    InternalDistributedMember neighbor = gmsHealthMonitor.getNextNeighbor();
    while (System.currentTimeMillis() < giveup && neighbor != expected) {
      Thread.sleep(5);
      neighbor = gmsHealthMonitor.getNextNeighbor();
    }

    // neighbor should change to 5th
    System.out.println("testHMNextNeighborAfterTimeout ending");
    Assert.assertEquals("expected " + expected + " but found " + neighbor
        + ".  view="+v, expected, neighbor);
  }

  /**
   * it checks neighbor before member-timeout, it should be same
   */

  @Test
  public void testHMNextNeighborBeforeTimeout() throws IOException {
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);

    try {
      // member-timeout is 1000 ms.  We initiate a check and choose
      // a new neighbor at 500 ms
      Thread.sleep(memberTimeout/GMSHealthMonitor.LOGICAL_INTERVAL - 100);
    } catch (InterruptedException e) {
    }
    // neighbor should be same
    System.out.println("next neighbor is " + gmsHealthMonitor.getNextNeighbor() +
        "\nmy address is " + mockMembers.get(3) +
        "\nview is " + v);

    Assert.assertEquals(mockMembers.get(4), gmsHealthMonitor.getNextNeighbor());
  }
  
  /***
   * checks whether member-check thread sends suspectMembers message
   */
  @Test
  public void testSuspectMembersCalledThroughMemberCheckThread() throws Exception {
    System.out.println("testSuspectMembersCalledThroughMemberCheckThread starting");
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);

    // when the view is installed we start a heartbeat timeout.  After
    // that expires we request a heartbeat
    Thread.sleep(3*memberTimeout + 100);

    System.out.println("testSuspectMembersCalledThroughMemberCheckThread ending");
    assertTrue(gmsHealthMonitor.isSuspectMember(mockMembers.get(4)));
  }

  /***
   * checks ping thread didn't sends suspectMembers message before timeout
   */
  @Test
  public void testSuspectMembersNotCalledThroughPingThreadBeforeTimeout() {
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));
    gmsHealthMonitor.started();
    
    gmsHealthMonitor.installView(v);
    InternalDistributedMember neighbor = gmsHealthMonitor.getNextNeighbor();

    try {
      // member-timeout is 1000 ms
      // plus 100 ms for ack
      Thread.sleep(memberTimeout - 200);
    } catch (InterruptedException e) {
    }

    assertFalse(gmsHealthMonitor.isSuspectMember(neighbor));
  }

  /***
   * Checks whether suspect thread sends suspectMembers message
   */
  @Test
  public void testSuspectMembersCalledThroughSuspectThread() throws Exception {
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());
    
    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));

    gmsHealthMonitor.installView(v);

    gmsHealthMonitor.suspect(mockMembers.get(1), "Not responding");

    Thread.sleep(GMSHealthMonitor.MEMBER_SUSPECT_COLLECTION_INTERVAL + 1000);

    verify(messenger, atLeastOnce()).send(any(SuspectMembersMessage.class));
  }

  /***
   * Checks suspect thread doesn't sends suspectMembers message before timeout
   */
  @Test
  public void testSuspectMembersNotCalledThroughSuspectThreadBeforeTimeout() {

    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    MethodExecuted messageSent = new MethodExecuted();
    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));

    gmsHealthMonitor.installView(v);

    gmsHealthMonitor.suspect(mockMembers.get(1), "Not responding");

    when(messenger.send(isA(SuspectMembersMessage.class))).thenAnswer(messageSent);

    try {
      // suspect thread timeout is 200 ms
      Thread.sleep(100l);
    } catch (InterruptedException e) {
    }

    assertTrue("SuspectMembersMessage shouldn't have sent", !messageSent.isMethodExecuted());
  }

  /***
   * Send remove member message after doing final check, ping Timeout
   */
  @Test
  public void testRemoveMemberCalled() throws Exception {
    System.out.println("testRemoveMemberCalled starting");
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(0)); // coordinator and local member
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);
    
    Thread.sleep(memberTimeout/GMSHealthMonitor.LOGICAL_INTERVAL);

    ArrayList<InternalDistributedMember> recipient = new ArrayList<InternalDistributedMember>();
    recipient.add(mockMembers.get(0));
    ArrayList<SuspectRequest> as = new ArrayList<SuspectRequest>();
    SuspectRequest sr = new SuspectRequest(mockMembers.get(1), "Not Responding");// removing member 1
    as.add(sr);
    SuspectMembersMessage sm = new SuspectMembersMessage(recipient, as);
    sm.setSender(mockMembers.get(0));

    gmsHealthMonitor.processMessage(sm);

    Thread.sleep(2*memberTimeout + 200);

    System.out.println("testRemoveMemberCalled ending");
    verify(joinLeave, atLeastOnce()).remove(any(InternalDistributedMember.class), any(String.class));
  }

  /***
   * Shouldn't send remove member message before doing final check, or before ping Timeout
   */
  @Test
  public void testRemoveMemberNotCalledBeforeTimeout() {
    System.out.println("testRemoveMemberNotCalledBeforeTimeout starting");
    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(0)); // coordinator and local member
    when(joinLeave.getMemberID()).thenReturn(mockMembers.get(0)); // coordinator and local member
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);

    ArrayList<InternalDistributedMember> recipient = new ArrayList<InternalDistributedMember>();
    recipient.add(mockMembers.get(0));
    ArrayList<SuspectRequest> as = new ArrayList<SuspectRequest>();
    SuspectRequest sr = new SuspectRequest(mockMembers.get(1), "Not Responding");// removing member 1
    as.add(sr);
    SuspectMembersMessage sm = new SuspectMembersMessage(recipient, as);
    sm.setSender(mockMembers.get(0));

    gmsHealthMonitor.processMessage(sm);

    try {
      // this happens after final check, ping timeout
      Thread.sleep(memberTimeout-100);
    } catch (InterruptedException e) {
    }

    System.out.println("testRemoveMemberNotCalledBeforeTimeout ending");
    verify(joinLeave, never()).remove(any(InternalDistributedMember.class), any(String.class));
  }

  /***
   * Send remove member message after doing final check for coordinator, ping timeout
   * This test trying to remove coordinator
   */
  @Test
  public void testRemoveMemberCalledAfterDoingFinalCheckOnCoordinator() throws Exception {

    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // preferred coordinators are 0 and 1
    when(messenger.getMemberID()).thenReturn(mockMembers.get(1));// next preferred coordinator
    gmsHealthMonitor.started();

    gmsHealthMonitor.installView(v);
    
    Thread.sleep(memberTimeout/GMSHealthMonitor.LOGICAL_INTERVAL);

    ArrayList<InternalDistributedMember> recipient = new ArrayList<InternalDistributedMember>();
    recipient.add(mockMembers.get(0));
    recipient.add(mockMembers.get(1));
    ArrayList<SuspectRequest> as = new ArrayList<SuspectRequest>();
    SuspectRequest sr = new SuspectRequest(mockMembers.get(0), "Not Responding");// removing coordinator
    as.add(sr);
    SuspectMembersMessage sm = new SuspectMembersMessage(recipient, as);
    sm.setSender(mockMembers.get(4));// member 4 sends suspect message

    gmsHealthMonitor.processMessage(sm);

    // this happens after final check, ping timeout = 1000 ms
    Thread.sleep(memberTimeout + 200);

    verify(joinLeave, atLeastOnce()).remove(any(InternalDistributedMember.class), any(String.class));
  }

  /***
   * validates HealthMonitor.CheckIfAvailable api
   */
  @Test
  public void testCheckIfAvailable() {

    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));

    gmsHealthMonitor.installView(v);

    long startTime = System.currentTimeMillis();

    boolean retVal = gmsHealthMonitor.checkIfAvailable(mockMembers.get(1), "Not responding", false);

    long timeTaken = System.currentTimeMillis() - startTime;

    assertTrue("This should have taken member ping timeout 100ms ", timeTaken > 90);
    assertTrue("CheckIfAvailable should have return false", !retVal);
  }

  @Test
  public void testShutdown() {

    NetView v = new NetView(mockMembers.get(0), 2, mockMembers, new HashSet<InternalDistributedMember>(), new HashSet<InternalDistributedMember>());

    // 3rd is current member
    when(messenger.getMemberID()).thenReturn(mockMembers.get(3));

    gmsHealthMonitor.installView(v);

    gmsHealthMonitor.stop();

    try {
      // this happens after final check, membertimeout = 1000
      Thread.sleep(100l);
    } catch (InterruptedException e) {
    }

    assertTrue("HeathMonitor should have shutdown", gmsHealthMonitor.isShutdown());

  }

  private class MethodExecuted implements Answer {
    private boolean methodExecuted = false;

    public boolean isMethodExecuted() {
      return methodExecuted;
    }

    @Override
    public Object answer(InvocationOnMock invocation) throws Throwable {
      methodExecuted = true;
      return null;
    }
  }
}
