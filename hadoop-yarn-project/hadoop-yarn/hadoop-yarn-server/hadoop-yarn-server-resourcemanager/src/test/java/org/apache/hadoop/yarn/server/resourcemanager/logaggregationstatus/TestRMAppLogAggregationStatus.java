/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.logaggregationstatus;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.LogAggregationStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.event.InlineDispatcher;
import org.apache.hadoop.yarn.server.api.protocolrecords.LogAggregationReport;
import org.apache.hadoop.yarn.server.api.records.NodeHealthStatus;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.RMContextImpl;
import org.apache.hadoop.yarn.server.resourcemanager.ahs.RMApplicationHistoryWriter;
import org.apache.hadoop.yarn.server.resourcemanager.metrics.SystemMetricsPublisher;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppRunningOnNodeEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeStartedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeStatusEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEventType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestRMAppLogAggregationStatus {

  private RMContext rmContext;
  private YarnScheduler scheduler;

  private SchedulerEventType eventType;

  private ApplicationId appId;


  private final class TestSchedulerEventDispatcher implements
  EventHandler<SchedulerEvent> {
    @Override
    public void handle(SchedulerEvent event) {
      scheduler.handle(event);
    }
  }

  @Before
  public void setUp() throws Exception {
    InlineDispatcher rmDispatcher = new InlineDispatcher();

    rmContext =
        new RMContextImpl(rmDispatcher, null, null, null,
          null, null, null, null, null,
          new RMApplicationHistoryWriter());
    rmContext.setSystemMetricsPublisher(new SystemMetricsPublisher());

    scheduler = mock(YarnScheduler.class);
    doAnswer(
        new Answer<Void>() {

          @Override
          public Void answer(InvocationOnMock invocation) throws Throwable {
            final SchedulerEvent event = (SchedulerEvent)(invocation.getArguments()[0]);
            eventType = event.getType();
            if (eventType == SchedulerEventType.NODE_UPDATE) {
              //DO NOTHING
            }
            return null;
          }
        }
        ).when(scheduler).handle(any(SchedulerEvent.class));

    rmDispatcher.register(SchedulerEventType.class,
        new TestSchedulerEventDispatcher());

    appId = ApplicationId.newInstance(System.currentTimeMillis(), 1);
  }

  @After
  public void tearDown() throws Exception {
  }

  @Test
  public void testLogAggregationStatus() throws Exception {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.LOG_AGGREGATION_ENABLED, true);
    conf.setLong(YarnConfiguration.LOG_AGGREGATION_STATUS_TIME_OUT_MS, 1500);
    RMApp rmApp = createRMApp(conf);
    this.rmContext.getRMApps().put(appId, rmApp);
    rmApp.handle(new RMAppEvent(this.appId, RMAppEventType.START));
    rmApp.handle(new RMAppEvent(this.appId, RMAppEventType.APP_NEW_SAVED));
    rmApp.handle(new RMAppEvent(this.appId, RMAppEventType.APP_ACCEPTED));

    // This application will be running on two nodes
    NodeId nodeId1 = NodeId.newInstance("localhost", 1234);
    Resource capability = Resource.newInstance(4096, 4);
    RMNodeImpl node1 =
        new RMNodeImpl(nodeId1, rmContext, null, 0, 0, null, capability, null);
    node1.handle(new RMNodeStartedEvent(nodeId1, null, null));
    rmApp.handle(new RMAppRunningOnNodeEvent(this.appId, nodeId1));

    NodeId nodeId2 = NodeId.newInstance("localhost", 2345);
    RMNodeImpl node2 =
        new RMNodeImpl(nodeId2, rmContext, null, 0, 0, null, capability, null);
    node2.handle(new RMNodeStartedEvent(node2.getNodeID(), null, null));
    rmApp.handle(new RMAppRunningOnNodeEvent(this.appId, nodeId2));

    // The initial log aggregation status for these two nodes
    // should be NOT_STARTED
    Map<NodeId, LogAggregationReport> logAggregationStatus =
        rmApp.getLogAggregationReportsForApp();
    Assert.assertEquals(2, logAggregationStatus.size());
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId1));
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId2));
    for (Entry<NodeId, LogAggregationReport> report : logAggregationStatus
      .entrySet()) {
      Assert.assertEquals(LogAggregationStatus.NOT_START, report.getValue()
        .getLogAggregationStatus());
    }

    Map<ApplicationId, LogAggregationReport> node1ReportForApp =
        new HashMap<ApplicationId, LogAggregationReport>();
    String messageForNode1_1 =
        "node1 logAggregation status updated at " + System.currentTimeMillis();
    LogAggregationReport report1 =
        LogAggregationReport.newInstance(appId, nodeId1,
          LogAggregationStatus.RUNNING, messageForNode1_1);
    node1ReportForApp.put(appId, report1);
    node1.handle(new RMNodeStatusEvent(node1.getNodeID(), NodeHealthStatus
      .newInstance(true, null, 0), new ArrayList<ContainerStatus>(), null,
      null, node1ReportForApp));

    Map<ApplicationId, LogAggregationReport> node2ReportForApp =
        new HashMap<ApplicationId, LogAggregationReport>();
    String messageForNode2_1 =
        "node2 logAggregation status updated at " + System.currentTimeMillis();
    LogAggregationReport report2 =
        LogAggregationReport.newInstance(appId, nodeId2,
          LogAggregationStatus.RUNNING, messageForNode2_1);
    node2ReportForApp.put(appId, report2);
    node2.handle(new RMNodeStatusEvent(node2.getNodeID(), NodeHealthStatus
      .newInstance(true, null, 0), new ArrayList<ContainerStatus>(), null,
      null, node2ReportForApp));
    // node1 and node2 has updated its log aggregation status
    // verify that the log aggregation status for node1, node2
    // has been changed
    logAggregationStatus = rmApp.getLogAggregationReportsForApp();
    Assert.assertEquals(2, logAggregationStatus.size());
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId1));
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId2));
    for (Entry<NodeId, LogAggregationReport> report : logAggregationStatus
      .entrySet()) {
      if (report.getKey().equals(node1.getNodeID())) {
        Assert.assertEquals(LogAggregationStatus.RUNNING, report.getValue()
          .getLogAggregationStatus());
        Assert.assertEquals(messageForNode1_1, report.getValue()
          .getDiagnosticMessage());
      } else if (report.getKey().equals(node2.getNodeID())) {
        Assert.assertEquals(LogAggregationStatus.RUNNING, report.getValue()
          .getLogAggregationStatus());
        Assert.assertEquals(messageForNode2_1, report.getValue()
          .getDiagnosticMessage());
      } else {
        // should not contain log aggregation report for other nodes
        Assert
          .fail("should not contain log aggregation report for other nodes");
      }
    }

    // node1 updates its log aggregation status again
    Map<ApplicationId, LogAggregationReport> node1ReportForApp2 =
        new HashMap<ApplicationId, LogAggregationReport>();
    String messageForNode1_2 =
        "node1 logAggregation status updated at " + System.currentTimeMillis();
    LogAggregationReport report1_2 =
        LogAggregationReport.newInstance(appId, nodeId1,
          LogAggregationStatus.RUNNING, messageForNode1_2);
    node1ReportForApp2.put(appId, report1_2);
    node1.handle(new RMNodeStatusEvent(node1.getNodeID(), NodeHealthStatus
      .newInstance(true, null, 0), new ArrayList<ContainerStatus>(), null,
      null, node1ReportForApp2));

    // verify that the log aggregation status for node1
    // has been changed
    // verify that the log aggregation status for node2
    // does not change
    logAggregationStatus = rmApp.getLogAggregationReportsForApp();
    Assert.assertEquals(2, logAggregationStatus.size());
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId1));
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId2));
    for (Entry<NodeId, LogAggregationReport> report : logAggregationStatus
      .entrySet()) {
      if (report.getKey().equals(node1.getNodeID())) {
        Assert.assertEquals(LogAggregationStatus.RUNNING, report.getValue()
          .getLogAggregationStatus());
        Assert.assertEquals(messageForNode1_1 + messageForNode1_2, report
          .getValue().getDiagnosticMessage());
      } else if (report.getKey().equals(node2.getNodeID())) {
        Assert.assertEquals(LogAggregationStatus.RUNNING, report.getValue()
          .getLogAggregationStatus());
        Assert.assertEquals(messageForNode2_1, report.getValue()
          .getDiagnosticMessage());
      } else {
        // should not contain log aggregation report for other nodes
        Assert
          .fail("should not contain log aggregation report for other nodes");
      }
    }

    // kill the application
    rmApp.handle(new RMAppEvent(appId, RMAppEventType.KILL));
    rmApp.handle(new RMAppEvent(appId, RMAppEventType.ATTEMPT_KILLED));
    rmApp.handle(new RMAppEvent(appId, RMAppEventType.APP_UPDATE_SAVED));
    Assert.assertEquals(RMAppState.KILLED, rmApp.getState());

    // wait for 1500 ms
    Thread.sleep(1500);

    // the log aggregation status for both nodes should be changed
    // to TIME_OUT
    logAggregationStatus = rmApp.getLogAggregationReportsForApp();
    Assert.assertEquals(2, logAggregationStatus.size());
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId1));
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId2));
    for (Entry<NodeId, LogAggregationReport> report : logAggregationStatus
      .entrySet()) {
      Assert.assertEquals(LogAggregationStatus.TIME_OUT, report.getValue()
        .getLogAggregationStatus());
    }

    // Finally, node1 finished its log aggregation and sent out its final
    // log aggregation status. The log aggregation status for node1 should
    // be changed from TIME_OUT to SUCCEEDED
    Map<ApplicationId, LogAggregationReport> node1ReportForApp3 =
        new HashMap<ApplicationId, LogAggregationReport>();
    String messageForNode1_3 =
        "node1 final logAggregation status updated at "
            + System.currentTimeMillis();
    LogAggregationReport report1_3 =
        LogAggregationReport.newInstance(appId, nodeId1,
          LogAggregationStatus.SUCCEEDED, messageForNode1_3);
    node1ReportForApp3.put(appId, report1_3);
    node1.handle(new RMNodeStatusEvent(node1.getNodeID(), NodeHealthStatus
      .newInstance(true, null, 0), new ArrayList<ContainerStatus>(), null,
      null, node1ReportForApp3));

    logAggregationStatus = rmApp.getLogAggregationReportsForApp();
    Assert.assertEquals(2, logAggregationStatus.size());
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId1));
    Assert.assertTrue(logAggregationStatus.containsKey(nodeId2));
    for (Entry<NodeId, LogAggregationReport> report : logAggregationStatus
      .entrySet()) {
      if (report.getKey().equals(node1.getNodeID())) {
        Assert.assertEquals(LogAggregationStatus.SUCCEEDED, report.getValue()
          .getLogAggregationStatus());
        Assert.assertEquals(messageForNode1_1 + messageForNode1_2
            + messageForNode1_3, report.getValue().getDiagnosticMessage());
      } else if (report.getKey().equals(node2.getNodeID())) {
        Assert.assertEquals(LogAggregationStatus.TIME_OUT, report.getValue()
          .getLogAggregationStatus());
      } else {
        // should not contain log aggregation report for other nodes
        Assert
          .fail("should not contain log aggregation report for other nodes");
      }
    }
  }

  @Test (timeout = 10000)
  public void testGetLogAggregationStatusForAppReport() {
    YarnConfiguration conf = new YarnConfiguration();

    // Disable the log aggregation
    conf.setBoolean(YarnConfiguration.LOG_AGGREGATION_ENABLED, false);
    RMAppImpl rmApp = (RMAppImpl)createRMApp(conf);
    // The log aggregation status should be DISABLED.
    Assert.assertEquals(LogAggregationStatus.DISABLED,
      rmApp.getLogAggregationStatusForAppReport());

    // Enable the log aggregation
    conf.setBoolean(YarnConfiguration.LOG_AGGREGATION_ENABLED, true);
    rmApp = (RMAppImpl)createRMApp(conf);
    // If we do not know any NodeManagers for this application ,
    // the log aggregation status will return null
    Assert.assertNull(rmApp.getLogAggregationStatusForAppReport());

    NodeId nodeId1 = NodeId.newInstance("localhost", 1111);
    NodeId nodeId2 = NodeId.newInstance("localhost", 2222);
    NodeId nodeId3 = NodeId.newInstance("localhost", 3333);
    NodeId nodeId4 = NodeId.newInstance("localhost", 4444);

    // If the log aggregation status for all NMs are NOT_START,
    // the log aggregation status for this app will return NOT_START
    rmApp.aggregateLogReport(nodeId1, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.NOT_START, ""));
    rmApp.aggregateLogReport(nodeId2, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.NOT_START, ""));
    rmApp.aggregateLogReport(nodeId3, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.NOT_START, ""));
    rmApp.aggregateLogReport(nodeId4, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.NOT_START, ""));
    Assert.assertEquals(LogAggregationStatus.NOT_START,
      rmApp.getLogAggregationStatusForAppReport());

    rmApp.aggregateLogReport(nodeId1, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.NOT_START, ""));
    rmApp.aggregateLogReport(nodeId2, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.RUNNING, ""));
    rmApp.aggregateLogReport(nodeId3, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId4, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.FAILED, ""));
    Assert.assertEquals(LogAggregationStatus.RUNNING,
      rmApp.getLogAggregationStatusForAppReport());

    rmApp.handle(new RMAppEvent(rmApp.getApplicationId(), RMAppEventType.KILL));
    Assert.assertTrue(RMAppImpl.isAppInFinalState(rmApp));

    // If at least of one log aggregation status for one NM is TIME_OUT,
    // others are SUCCEEDED, the log aggregation status for this app will
    // return TIME_OUT
    rmApp.aggregateLogReport(nodeId1, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId2, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.TIME_OUT, ""));
    rmApp.aggregateLogReport(nodeId3, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId4, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    Assert.assertEquals(LogAggregationStatus.TIME_OUT,
      rmApp.getLogAggregationStatusForAppReport());

    // If the log aggregation status for all NMs are SUCCEEDED and Application
    // is at the final state, the log aggregation status for this app will
    // return SUCCEEDED
    rmApp.aggregateLogReport(nodeId1, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId2, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId3, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId4, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    Assert.assertEquals(LogAggregationStatus.SUCCEEDED,
      rmApp.getLogAggregationStatusForAppReport());

    rmApp = (RMAppImpl)createRMApp(conf);
    rmApp.handle(new RMAppEvent(rmApp.getApplicationId(), RMAppEventType.KILL));
    Assert.assertTrue(RMAppImpl.isAppInFinalState(rmApp));
    // If at least of one log aggregation status for one NM is FAILED,
    // others are either SUCCEEDED or TIME_OUT, and this application is
    // at the final state, the log aggregation status for this app
    // will return FAILED
    rmApp.aggregateLogReport(nodeId1, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    rmApp.aggregateLogReport(nodeId2, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.TIME_OUT, ""));
    rmApp.aggregateLogReport(nodeId3, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.FAILED, ""));
    rmApp.aggregateLogReport(nodeId4, LogAggregationReport.newInstance(
      rmApp.getApplicationId(), nodeId1, LogAggregationStatus.SUCCEEDED, ""));
    Assert.assertEquals(LogAggregationStatus.FAILED,
      rmApp.getLogAggregationStatusForAppReport());

  }

  private RMApp createRMApp(Configuration conf) {
    ApplicationSubmissionContext submissionContext =
        ApplicationSubmissionContext.newInstance(appId, "test", "default",
          Priority.newInstance(0), null, false, true,
          2, Resource.newInstance(10, 2), "test");
    return new RMAppImpl(this.appId, this.rmContext,
      conf, "test", "test", "default", submissionContext,
      this.rmContext.getScheduler(),
      this.rmContext.getApplicationMasterService(),
      System.currentTimeMillis(), "test",
      null, null);
  }
}
