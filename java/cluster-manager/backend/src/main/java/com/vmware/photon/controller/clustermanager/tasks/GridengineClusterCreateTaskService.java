/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.vmware.photon.controller.clustermanager.tasks;

import com.vmware.photon.controller.api.ClusterState;
import com.vmware.photon.controller.cloudstore.xenon.entity.ClusterService;
import com.vmware.photon.controller.cloudstore.xenon.entity.ClusterServiceFactory;
import com.vmware.photon.controller.clustermanager.rolloutplans.BasicNodeRollout;
import com.vmware.photon.controller.clustermanager.rolloutplans.NodeRollout;
import com.vmware.photon.controller.clustermanager.rolloutplans.NodeRolloutInput;
import com.vmware.photon.controller.clustermanager.rolloutplans.NodeRolloutResult;
import com.vmware.photon.controller.clustermanager.rolloutplans.SlavesNodeRollout;
import com.vmware.photon.controller.clustermanager.servicedocuments.ClusterManagerConstants;
import com.vmware.photon.controller.clustermanager.servicedocuments.GridengineClusterCreateTask;
import com.vmware.photon.controller.clustermanager.servicedocuments.GridengineClusterCreateTask.TaskState;
import com.vmware.photon.controller.clustermanager.servicedocuments.NodeType;
import com.vmware.photon.controller.clustermanager.templates.GridengineMasterNodeTemplate;
import com.vmware.photon.controller.clustermanager.templates.GridengineSlaveNodeTemplate;
import com.vmware.photon.controller.clustermanager.templates.NfsNodeTemplate;
//import com.vmware.photon.controller.clustermanager.templates.NodeTemplateUtils;
import com.vmware.photon.controller.clustermanager.utils.HostUtils;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.InitializationUtils;
import com.vmware.photon.controller.common.xenon.PatchUtils;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.ValidationUtils;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

import com.google.common.util.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;





/**
 * This class implements a Xenon service representing a task to create a
 * Gridengine cluster.
 */
public class GridengineClusterCreateTaskService extends StatefulService {

  private static final int MINIMUM_INITIAL_SLAVE_COUNT = 1;
    private static final Logger logger = LoggerFactory.getLogger(GridengineClusterCreateTaskService.class);

  public GridengineClusterCreateTaskService() {
    super(GridengineClusterCreateTask.class);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
  }

  @Override
  public void handleStart(Operation start) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    GridengineClusterCreateTask startState = start.getBody(GridengineClusterCreateTask.class);
    InitializationUtils.initialize(startState);
    validateStartState(startState);

    if (startState.taskState.stage == TaskState.TaskStage.CREATED) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
      startState.taskState.subStage = TaskState.SubStage.SETUP_NFS;
    }

    if (startState.documentExpirationTimeMicros <= 0) {
      startState.documentExpirationTimeMicros = ServiceUtils
          .computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME_MICROS);
    }

    start.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        TaskUtils.sendSelfPatch(this, buildPatch(startState.taskState.stage, TaskState.SubStage.SETUP_NFS));
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  @Override
  public void handlePatch(Operation patch) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    GridengineClusterCreateTask currentState = getState(patch);
    GridengineClusterCreateTask patchState = patch.getBody(GridengineClusterCreateTask.class);
    validatePatchState(currentState, patchState);
    PatchUtils.patchState(currentState, patchState);
    validateStartState(currentState);
    patch.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch handling (disabled)");
      } else if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
        processStateMachine(currentState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void processStateMachine(GridengineClusterCreateTask currentState) {
    switch (currentState.taskState.subStage) {
    case SETUP_NFS:
      setupNFS(currentState);
      break;

    case SETUP_MASTER:
      setupMaster(currentState);
      break;

    case SETUP_SLAVES:
      setupInitialSlaves(currentState);
      break;

    default:
      throw new IllegalStateException("Unknown sub-stage: " + currentState.taskState.subStage);
    }
  }

  /**
   * This method roll-outs NFS node. On successful rollout, the methods moves
   * the task sub-stage to SETUP_MASTER.
   *
   * @param currentState
   */
  private void setupNFS(GridengineClusterCreateTask currentState) {
    sendRequest(
        HostUtils.getCloudStoreHelper(this).createGet(ClusterServiceFactory.SELF_LINK + "/" + currentState.clusterId)
            .setReferer(getUri()).setCompletion((operation, throwable) -> {
              if (null != throwable) {
                failTask(throwable);
                return;
              }

              ClusterService.State cluster = operation.getBody(ClusterService.State.class);

              NodeRolloutInput rolloutInput = new NodeRolloutInput();
              rolloutInput.projectId = cluster.projectId;
              rolloutInput.imageId = cluster.imageId;
              rolloutInput.vmFlavorName = cluster.otherVmFlavorName;
              rolloutInput.diskFlavorName = cluster.diskFlavorName;
              rolloutInput.vmNetworkId = cluster.vmNetworkId;
              rolloutInput.clusterId = currentState.clusterId;
              rolloutInput.nodeCount = ClusterManagerConstants.Gridengine.MASTER_COUNT;
              rolloutInput.nodeType = NodeType.GridengineNFS;
              rolloutInput.nodeProperties = NfsNodeTemplate.createProperties(
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_DNS),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_GATEWAY),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_NETMASK),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_NFS_IP));

              NodeRollout rollout = new BasicNodeRollout();
              rollout.run(this, rolloutInput, new FutureCallback<NodeRolloutResult>() {
                @Override
                public void onSuccess(@Nullable NodeRolloutResult result) {
                  TaskUtils.sendSelfPatch(GridengineClusterCreateTaskService.this,
                      buildPatch(GridengineClusterCreateTask.TaskState.TaskStage.STARTED,
                          GridengineClusterCreateTask.TaskState.SubStage.SETUP_MASTER));
                }

                @Override
                public void onFailure(Throwable t) {
                  failTaskAndPatchDocument(currentState, NodeType.GridengineNFS, t);
                }
              });
            }));
  }

  /**
   * This method roll-outs GridEngine Master Nodes. On successful roll-out, the
   * methods moves the task sub-stage to SETUP_SLAVES.
   *
   * @param currentState
   */
  private void setupMaster(final GridengineClusterCreateTask currentState) {
    sendRequest(
        HostUtils.getCloudStoreHelper(this).createGet(ClusterServiceFactory.SELF_LINK + "/" + currentState.clusterId)
            .setReferer(getUri()).setCompletion((operation, throwable) -> {
              if (null != throwable) {
                failTask(throwable);
                return;
              }

              ClusterService.State cluster = operation.getBody(ClusterService.State.class);

              NodeRolloutInput rolloutInput = new NodeRolloutInput();
              rolloutInput.clusterId = currentState.clusterId;
              rolloutInput.projectId = cluster.projectId;
              rolloutInput.imageId = cluster.imageId;
              rolloutInput.diskFlavorName = cluster.diskFlavorName;
              rolloutInput.vmFlavorName = cluster.masterVmFlavorName;
              rolloutInput.vmNetworkId = cluster.vmNetworkId;
              rolloutInput.nodeCount = ClusterManagerConstants.Gridengine.MASTER_COUNT;
              rolloutInput.nodeType = NodeType.GridengineMaster;
              rolloutInput.nodeProperties = GridengineMasterNodeTemplate.createProperties(
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_NFS_IP),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_DNS),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_GATEWAY),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_NETMASK),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_MASTER_IP));

              NodeRollout rollout = new BasicNodeRollout();
              rollout.run(this, rolloutInput, new FutureCallback<NodeRolloutResult>() {
                @Override
                public void onSuccess(@Nullable NodeRolloutResult result) {
                  TaskUtils.sendSelfPatch(GridengineClusterCreateTaskService.this,
                      buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.SETUP_SLAVES));
                }

                @Override
                public void onFailure(Throwable t) {
                  failTaskAndPatchDocument(currentState, NodeType.GridengineMaster, t);
                }
              });
            }));
  }

  /**
   * This method roll-outs the initial Gridengine Slave Nodes. On successful
   * roll-out, the method creates necessary tasks for cluster maintenance.
   *
   * @param currentState
   */
  private void setupInitialSlaves(final GridengineClusterCreateTask currentState) {
    sendRequest(
        HostUtils.getCloudStoreHelper(this).createGet(ClusterServiceFactory.SELF_LINK + "/" + currentState.clusterId)
            .setReferer(getUri()).setCompletion((operation, throwable) -> {
              if (null != throwable) {
                failTask(throwable);
                return;
              }

              ClusterService.State cluster = operation.getBody(ClusterService.State.class);

              logger.info("Chaoc: Number of Slave nodes for Grid Engine Cluster: {}", cluster.slaveCount);

              NodeRolloutInput rolloutInput = new NodeRolloutInput();
              rolloutInput.clusterId = currentState.clusterId;
              rolloutInput.projectId = cluster.projectId;
              rolloutInput.imageId = cluster.imageId;
              rolloutInput.diskFlavorName = cluster.diskFlavorName;
              rolloutInput.vmFlavorName = cluster.otherVmFlavorName;
              rolloutInput.vmNetworkId = cluster.vmNetworkId;
              //rolloutInput.nodeCount = MINIMUM_INITIAL_SLAVE_COUNT;
              rolloutInput.nodeCount = cluster.slaveCount;
              rolloutInput.nodeType = NodeType.GridengineSlave;
              rolloutInput.serverAddress = cluster.extendedProperties
                  .get(ClusterManagerConstants.EXTENDED_PROPERTY_MASTER_IP);
              rolloutInput.nodeProperties = GridengineSlaveNodeTemplate.createProperties(
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_NFS_IP),
                  cluster.extendedProperties.get(ClusterManagerConstants.EXTENDED_PROPERTY_MASTER_IP));

              NodeRollout rollout = new SlavesNodeRollout();
              rollout.run(this, rolloutInput, new FutureCallback<NodeRolloutResult>() {
                @Override
                public void onSuccess(@Nullable NodeRolloutResult result) {
                  //setupRemainingSlaves(currentState, cluster);
                    GridengineClusterCreateTask patchState = buildPatch(TaskState.TaskStage.FINISHED, null);

                    ClusterService.State clusterPatch = new ClusterService.State();
                    clusterPatch.clusterState = ClusterState.READY;

                    updateStates(currentState, patchState, clusterPatch);
                    startMaintenance(currentState);
                }

                @Override
                public void onFailure(Throwable t) {
                  failTaskAndPatchDocument(currentState, NodeType.GridengineSlave, t);
                }
              });
            }));
  }

  /*
  private void setupRemainingSlaves(final GridengineClusterCreateTask currentState,
      final ClusterService.State cluster) {
    // Maintenance task should be singleton for any cluster.
    ClusterMaintenanceTaskService.State startState = new ClusterMaintenanceTaskService.State();
    startState.batchExpansionSize = currentState.slaveBatchExpansionSize;
    startState.documentSelfLink = currentState.clusterId;

    Operation postOperation = Operation
        .createPost(UriUtils.buildUri(getHost(), ClusterMaintenanceTaskFactoryService.SELF_LINK)).setBody(startState)
        .setCompletion((Operation operation, Throwable throwable) -> {
          if (null != throwable) {
            failTaskAndPatchDocument(currentState, NodeType.GridengineSlave, throwable);
            return;
          }

          logger.info("Chaoc: Setup the remaining of Slave Nodes. Number of Slave nodes: {}", cluster.slaveCount);
          if (cluster.slaveCount == MINIMUM_INITIAL_SLAVE_COUNT) {
            // We short circuit here and set the clusterState as READY, since
            // the desired size has
            // already been reached. Maintenance will kick-in when the
            // maintenance interval elapses.
            GridengineClusterCreateTask patchState = buildPatch(TaskState.TaskStage.FINISHED, null);

            ClusterService.State clusterPatch = new ClusterService.State();
            clusterPatch.clusterState = ClusterState.READY;

            updateStates(currentState, patchState, clusterPatch);
          } else {
            // The handleStart method of the maintenance task does not push
            // itself to STARTED automatically.
            // We need to patch the maintenance task manually to start the task
            // immediately. Otherwise
            // the task will wait for one interval to start.
            startMaintenance(currentState);
          }
        });
    sendRequest(postOperation);
  }
  */

  private void startMaintenance(final GridengineClusterCreateTask currentState) {
    ClusterMaintenanceTaskService.State patchState = new ClusterMaintenanceTaskService.State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = TaskState.TaskStage.STARTED;

    // Start the maintenance task async without waiting for its completion so
    // that the creation task
    // can finish immediately.
    Operation patchOperation = Operation
        .createPatch(
            UriUtils.buildUri(getHost(), ClusterMaintenanceTaskFactoryService.SELF_LINK + "/" + currentState.clusterId))
        .setBody(patchState).setCompletion((Operation operation, Throwable throwable) -> {
          // We ignore the failure here since maintenance task will kick in
          // eventually.
          TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FINISHED, null));
        });
    sendRequest(patchOperation);
  }

  private void validateStartState(GridengineClusterCreateTask startState) {
    ValidationUtils.validateState(startState);
    ValidationUtils.validateTaskStage(startState.taskState);

    if (startState.taskState.stage == TaskState.TaskStage.STARTED) {
      checkState(startState.taskState.subStage != null, "Sub-stage cannot be null in STARTED stage.");

      switch (startState.taskState.subStage) {
      case SETUP_NFS:
      case SETUP_MASTER:
      case SETUP_SLAVES:
        break;
      default:
        throw new IllegalStateException("Unknown task sub-stage: " + startState.taskState.subStage.toString());
      }
    } else {
      checkState(startState.taskState.subStage == null, "Sub-stage must be null in stages other than STARTED.");
    }
  }

  private void validatePatchState(GridengineClusterCreateTask currentState, GridengineClusterCreateTask patchState) {
    ValidationUtils.validatePatch(currentState, patchState);
    ValidationUtils.validateTaskStage(patchState.taskState);
    ValidationUtils.validateTaskStageProgression(currentState.taskState, patchState.taskState);

    if (currentState.taskState.subStage != null && patchState.taskState.subStage != null) {
      checkState(patchState.taskState.subStage.ordinal() >= currentState.taskState.subStage.ordinal());
    }

    if (patchState.taskState.stage == TaskState.TaskStage.STARTED) {
      checkNotNull(patchState.taskState.subStage);
    }
  }

  private GridengineClusterCreateTask buildPatch(TaskState.TaskStage stage, TaskState.SubStage subStage) {
    return buildPatch(stage, subStage, (Throwable) null);
  }

  private GridengineClusterCreateTask buildPatch(TaskState.TaskStage stage, TaskState.SubStage subStage,
      @Nullable Throwable t) {
    return buildPatch(stage, subStage, null == t ? null : Utils.toServiceErrorResponse(t));
  }

  private GridengineClusterCreateTask buildPatch(TaskState.TaskStage stage, TaskState.SubStage subStage,
      @Nullable ServiceErrorResponse errorResponse) {

    GridengineClusterCreateTask state = new GridengineClusterCreateTask();
    state.taskState = new TaskState();
    state.taskState.stage = stage;
    state.taskState.subStage = subStage;
    state.taskState.failure = errorResponse;
    return state;
  }

  private void failTaskAndPatchDocument(final GridengineClusterCreateTask currentState, final NodeType nodeType,
      final Throwable throwable) {
    ServiceUtils.logSevere(this, throwable);
    GridengineClusterCreateTask patchState = buildPatch(TaskState.TaskStage.FAILED, null, new IllegalStateException(
        String.format("Failed to rollout %s. Error: %s", nodeType.toString(), throwable.toString())));

    ClusterService.State document = new ClusterService.State();
    document.clusterState = ClusterState.ERROR;
    updateStates(currentState, patchState, document);
  }

  private void failTask(Throwable e) {
    ServiceUtils.logSevere(this, e);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, e));
  }

  private void updateStates(final GridengineClusterCreateTask currentState,
      final GridengineClusterCreateTask patchState, final ClusterService.State clusterPatchState) {

    sendRequest(
        HostUtils.getCloudStoreHelper(this).createPatch(ClusterServiceFactory.SELF_LINK + "/" + currentState.clusterId)
            .setBody(clusterPatchState).setCompletion((Operation operation, Throwable throwable) -> {
              if (null != throwable) {
                failTask(throwable);
                return;
              }

              TaskUtils.sendSelfPatch(GridengineClusterCreateTaskService.this, patchState);
            }));
  }
}
