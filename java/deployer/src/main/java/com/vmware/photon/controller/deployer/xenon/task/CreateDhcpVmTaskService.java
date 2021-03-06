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

package com.vmware.photon.controller.deployer.xenon.task;

import com.vmware.photon.controller.api.AttachedDiskCreateSpec;
import com.vmware.photon.controller.api.EphemeralDisk;
import com.vmware.photon.controller.api.LocalitySpec;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.api.VmCreateSpec;
import com.vmware.photon.controller.api.VmMetadata;
import com.vmware.photon.controller.cloudstore.xenon.entity.HostService;
import com.vmware.photon.controller.common.Constants;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.InitializationUtils;
import com.vmware.photon.controller.common.xenon.PatchUtils;
import com.vmware.photon.controller.common.xenon.ServiceUtils;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.ValidationUtils;
import com.vmware.photon.controller.common.xenon.deployment.NoMigrationDuringDeployment;
import com.vmware.photon.controller.common.xenon.migration.NoMigrationDuringUpgrade;
import com.vmware.photon.controller.common.xenon.validation.DefaultInteger;
import com.vmware.photon.controller.common.xenon.validation.DefaultTaskState;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.NotNull;
import com.vmware.photon.controller.common.xenon.validation.Positive;
import com.vmware.photon.controller.common.xenon.validation.WriteOnce;
import com.vmware.photon.controller.deployer.configuration.LoadBalancerServer;
import com.vmware.photon.controller.deployer.configuration.PeerNode;
import com.vmware.photon.controller.deployer.configuration.ServiceConfigurator;
import com.vmware.photon.controller.deployer.configuration.ZookeeperServer;
import com.vmware.photon.controller.deployer.deployengine.ScriptRunner;
import com.vmware.photon.controller.deployer.healthcheck.HealthChecker;
import com.vmware.photon.controller.deployer.xenon.ContainersConfig;
import com.vmware.photon.controller.deployer.xenon.DeployerContext;
import com.vmware.photon.controller.deployer.xenon.entity.ContainerService;
import com.vmware.photon.controller.deployer.xenon.entity.ContainerTemplateService;
import com.vmware.photon.controller.deployer.xenon.entity.VmService;
import com.vmware.photon.controller.deployer.xenon.util.ApiUtils;
import com.vmware.photon.controller.deployer.xenon.util.HostUtils;
import com.vmware.photon.controller.deployer.xenon.workflow.BuildContainersConfigurationWorkflowService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.ServiceUriPaths;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.io.FileUtils;
import org.apache.commons.net.util.SubnetUtils;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class implements a Xenon task service which creates a management VM.
 */
public class CreateDhcpVmTaskService extends StatefulService {

  @VisibleForTesting
  public static final String SCRIPT_NAME = "esx-create-vm-iso";

  private static final TypeToken loadBalancerTypeToken = new TypeToken<ArrayList<LoadBalancerServer>>() {
  };

  private static final TypeToken peerNodeTypeToken = new TypeToken<ArrayList<PeerNode>>() {
  };

  private static final TypeToken zookeeperTypeToken = new TypeToken<ArrayList<ZookeeperServer>>() {
  };

  /**
   * This class defines the state of a {@link CreateDhcpVmTaskService} task.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {

    /**
     * This class defines the possible sub-stages for a task.
     */
    public enum SubStage {
      CREATE_VM,
      WAIT_FOR_VM_CREATION,
      UPDATE_METADATA,
      WAIT_FOR_METADATA_UPDATE,
      ATTACH_ISO,
      WAIT_FOR_ATTACH_ISO,
      START_VM,
      WAIT_FOR_VM_START,
      WAIT_FOR_DHCP_AGENT,
    }

    /**
     * This value defines the sub-stage of the current task.
     */
    public SubStage subStage;
  }

  /**
   * This class defines the document state associated with a {@link CreateDhcpVmTaskService} task.
   */
  @NoMigrationDuringUpgrade
  @NoMigrationDuringDeployment
  public static class State extends ServiceDocument {

    /**
     * This value represents the state of the current task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.CREATED)
    public TaskState taskState;

    /**
     * This value represents the control flags for the current task.
     */
    @DefaultInteger(value = 0)
    public Integer controlFlags;

    /**
     * This value represents the optional document self-link of the parent task service to be
     * notified on completion.
     */
    @Immutable
    public String parentTaskServiceLink;

    /**
     * This value represents the optional patch body to be sent to the parent task service on
     * successful completion.
     */
    @Immutable
    public String parentPatchBody;

    /**
     * This value represents the document self-link of the {@link VmService} to be created.
     */
    @NotNull
    @Immutable
    public String vmServiceLink;

    /**
     * This value represents the NTP endpoint for the current deployment.
     */
    @Immutable
    public String ntpEndpoint;

    /**
     * This value represents the delay, in milliseconds, to use when polling a child task.
     */
    @Positive
    public Integer taskPollDelay;

    /**
     * This value represents the ID of the API-level task to create the VM.
     */
    @WriteOnce
    public String createVmTaskId;

    /**
     * This value represents the number of polling iterations which have been performed by the
     * current task while waiting for the VM to be created. It is informational only.
     */
    @DefaultInteger(value = 0)
    public Integer createVmPollCount;

    /**
     * This value represents the ID of the API-level VM object created by the current task.
     */
    @WriteOnce
    public String vmId;

    /**
     * This value represents the ID of the API-level task to update the VM metadata.
     */
    @WriteOnce
    public String updateVmMetadataTaskId;

    /**
     * This value represents the number of polling iterations which have been performed by the
     * current task while waiting for the VM metadata to be updated. It is informational only.
     */
    @DefaultInteger(value = 0)
    public Integer updateVmMetadataPollCount;

    /**
     * This value represents the path on the local file system of the working directory which is
     * used by the current task to generate the container configuration information for the VM. It
     * is informational only.
     */
    @WriteOnce
    public String serviceConfigDirectory;

    /**
     * This value represents the path on the local file system of the configuration ISO which was
     * generated for the VM. It is informational only.
     */
    @WriteOnce
    public String vmConfigDirectory;

    /**
     * This value represents the ID of the API-level task to upload and attach the settings ISO to
     * the VM.
     */
    @WriteOnce
    public String attachIsoTaskId;

    /**
     * This value represents the number of polling iterations which have been performed by the
     * current task while waiting for the settings ISO to be attached to the VM. It is informational
     * only.
     */
    @DefaultInteger(value = 0)
    public Integer attachIsoPollCount;

    /**
     * This value represents the ID of the API-level task to power on the VM.
     */
    @WriteOnce
    public String startVmTaskId;

    /**
     * This value represents the number of polling iterations which have been performed by the
     * current task while waiting for the VM to be powered on. It is informational only.
     */
    @DefaultInteger(value = 0)
    public Integer startVmPollCount;

    /**
     * This value represents the IP address of the DHCP Agent endpoint on the remote VM.
     */
    @WriteOnce
    public String dhcpAgentEndpointAddress;

    /**
     * This value represents the maximum number of polling iterations to perform while waiting for
     * the DHCP Agent to report READY state.
     */
    @DefaultInteger(value = 600)
    @Positive
    @Immutable
    public Integer maxDhcpAgentPollIterations;

    /**
     * This value represents the number of polling iterations which have been performed by the
     * current task while waiting for the DHCP Agent to come up on the remote machine.
     */
    @DefaultInteger(value = 0)
    public Integer dhcpAgentPollIterations;
  }

  public CreateDhcpVmTaskService() {
    super(State.class);
  }

  @Override
  public void handleStart(Operation startOp) {
    ServiceUtils.logTrace(this, "Handling start operation");
    State startState = startOp.getBody(State.class);
    InitializationUtils.initialize(startState);

    if (startState.taskPollDelay == null) {
      startState.taskPollDelay = HostUtils.getDeployerContext(this).getTaskPollDelay();
    }

    validateState(startState);

    if (startState.taskState.stage == TaskState.TaskStage.CREATED) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
      startState.taskState.subStage = TaskState.SubStage.CREATE_VM;
    }

    if (startState.documentExpirationTimeMicros <= 0) {
      startState.documentExpirationTimeMicros =
          ServiceUtils.computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME_MICROS);
    }

    startOp.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (startState.taskState.stage == TaskState.TaskStage.STARTED) {
        sendStageProgressPatch(startState.taskState.stage, startState.taskState.subStage);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  @Override
  public void handlePatch(Operation patchOp) {
    ServiceUtils.logTrace(this, "Handling patch operation");
    State currentState = getState(patchOp);
    State patchState = patchOp.getBody(State.class);
    validatePatch(currentState, patchState);
    PatchUtils.patchState(currentState, patchState);
    validateState(currentState);
    patchOp.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
      } else if (currentState.taskState.stage == TaskState.TaskStage.STARTED) {
        processStartedStage(currentState);
      } else {
        TaskUtils.notifyParentTask(this, currentState.taskState, currentState.parentTaskServiceLink,
            currentState.parentPatchBody);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    validateTaskState(currentState.taskState);
  }

  private void validatePatch(State currentState, State patchState) {
    ValidationUtils.validatePatch(currentState, patchState);
    validateTaskState(patchState.taskState);
    validateTaskStageProgression(currentState.taskState, patchState.taskState);
  }

  private void validateTaskState(TaskState taskState) {
    ValidationUtils.validateTaskStage(taskState);
    switch (taskState.stage) {
      case CREATED:
      case FINISHED:
      case FAILED:
      case CANCELLED:
        checkState(taskState.subStage == null);
        break;
      case STARTED:
        checkState(taskState.subStage != null);
        switch (taskState.subStage) {
          case CREATE_VM:
          case WAIT_FOR_VM_CREATION:
          case UPDATE_METADATA:
          case WAIT_FOR_METADATA_UPDATE:
          case ATTACH_ISO:
          case WAIT_FOR_ATTACH_ISO:
          case START_VM:
          case WAIT_FOR_VM_START:
          case WAIT_FOR_DHCP_AGENT:
            break;
          default:
            throw new IllegalStateException("Unknown task sub-stage: " + taskState.subStage);
        }
    }
  }

  private void validateTaskStageProgression(TaskState currentState, TaskState patchState) {
    ValidationUtils.validateTaskStageProgression(currentState, patchState);
    if (currentState.subStage != null && patchState.subStage != null) {
      checkState(patchState.subStage.ordinal() >= currentState.subStage.ordinal());
    }
  }

  private void processStartedStage(State currentState) throws Throwable {
    switch (currentState.taskState.subStage) {
      case CREATE_VM:
        processCreateVmSubStage(currentState);
        break;
      case WAIT_FOR_VM_CREATION:
        processWaitForVmCreationSubStage(currentState);
        break;
      case UPDATE_METADATA:
        processUpdateMetadataSubStage(currentState);
        break;
      case WAIT_FOR_METADATA_UPDATE:
        processWaitForMetadataUpdateSubStage(currentState);
        break;
      case ATTACH_ISO:
        processAttachIsoSubStage(currentState);
        break;
      case WAIT_FOR_ATTACH_ISO:
        processWaitForAttachIsoSubStage(currentState);
        break;
      case START_VM:
        processStartVmSubStage(currentState);
        break;
      case WAIT_FOR_VM_START:
        processWaitForVmStartSubStage(currentState);
        break;
      case WAIT_FOR_DHCP_AGENT:
        processWaitForDhcpAgentSubStage(currentState);
        break;
    }
  }

  //
  // CREATE_VM sub-stage methods
  //

  private void processCreateVmSubStage(State currentState) {

    sendRequest(Operation
        .createGet(this, currentState.vmServiceLink)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                processCreateVmSubStage(currentState, o.getBody(VmService.State.class));
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void processCreateVmSubStage(State currentState, VmService.State vmState) {

    sendRequest(HostUtils.getCloudStoreHelper(this)
        .createGet(vmState.hostServiceLink)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                processCreateVmSubStage(currentState, vmState, o.getBody(HostService.State.class));
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void processCreateVmSubStage(State currentState,
                                       VmService.State vmState,
                                       HostService.State hostState)
      throws Throwable {

    AttachedDiskCreateSpec bootDiskCreateSpec = new AttachedDiskCreateSpec();
    bootDiskCreateSpec.setName(vmState.name + "-bootdisk");
    bootDiskCreateSpec.setBootDisk(true);
    bootDiskCreateSpec.setFlavor("mgmt-vm-disk-" + vmState.name);
    bootDiskCreateSpec.setKind(EphemeralDisk.KIND);

    LocalitySpec hostLocalitySpec = new LocalitySpec();
    hostLocalitySpec.setId(hostState.hostAddress);
    hostLocalitySpec.setKind("host");

    LocalitySpec datastoreLocalitySpec = new LocalitySpec();
    checkState(hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_MANAGEMENT_DATASTORE));
    datastoreLocalitySpec.setId(hostState.metadata.get(HostService.State.METADATA_KEY_NAME_MANAGEMENT_DATASTORE));
    datastoreLocalitySpec.setKind("datastore");

    LocalitySpec portGroupLocalitySpec = new LocalitySpec();
    checkState(hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_MANAGEMENT_PORTGROUP));
    portGroupLocalitySpec.setId(hostState.metadata.get(HostService.State.METADATA_KEY_NAME_MANAGEMENT_PORTGROUP));
    portGroupLocalitySpec.setKind("portGroup");

    VmCreateSpec vmCreateSpec = new VmCreateSpec();
    vmCreateSpec.setName(vmState.name);
    vmCreateSpec.setFlavor("mgmt-vm-" + vmState.name);
    vmCreateSpec.setSourceImageId(vmState.imageId);
    vmCreateSpec.setEnvironment(new HashMap<>());
    vmCreateSpec.setAttachedDisks(Collections.singletonList(bootDiskCreateSpec));
    vmCreateSpec.setAffinities(Arrays.asList(hostLocalitySpec, datastoreLocalitySpec, portGroupLocalitySpec));

    HostUtils.getApiClient(this)
        .getProjectApi()
        .createVmAsync(vmState.projectId, vmCreateSpec,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processCreateVmTaskResult(currentState, task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processCreateVmTaskResult(State currentState, Task task) {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_VM_CREATION, null);
        patchState.createVmTaskId = task.getId();
        patchState.createVmPollCount = 1;
        sendStageProgressPatch(patchState);
        break;
      case "COMPLETED":
        updateVmId(currentState, task.getEntity().getId());
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // WAIT_FOR_VM sub-stage routines
  //

  private void processWaitForVmCreationSubStage(State currentState) throws Throwable {

    HostUtils.getApiClient(this)
        .getTasksApi()
        .getTaskAsync(currentState.createVmTaskId,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processVmCreationTaskResult(currentState, task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processVmCreationTaskResult(State currentState, Task task) {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        getHost().schedule(
            () -> {
              State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_VM_CREATION, null);
              patchState.createVmPollCount = currentState.createVmPollCount + 1;
              TaskUtils.sendSelfPatch(this, patchState);
            },
            currentState.taskPollDelay, TimeUnit.MILLISECONDS);
        break;
      case "COMPLETED":
        updateVmId(currentState, task.getEntity().getId());
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  private void updateVmId(State currentState, String vmId) {

    VmService.State vmPatchState = new VmService.State();
    vmPatchState.vmId = vmId;

    sendRequest(Operation
        .createPatch(this, currentState.vmServiceLink)
        .setBody(vmPatchState)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.UPDATE_METADATA, null);
                patchState.vmId = vmId;
                sendStageProgressPatch(patchState);
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  //
  // UPDATE_METADATA sub-stage routines
  //

  private void processUpdateMetadataSubStage(State currentState) {

    QueryTask containerQueryTask = QueryTask.Builder.createDirectTask()
        .setQuery(QueryTask.Query.Builder.create()
            .addKindFieldClause(ContainerService.State.class)
            .addFieldClause(ContainerService.State.FIELD_NAME_VM_SERVICE_LINK, currentState.vmServiceLink)
            .build())
        .addOptions(EnumSet.of(
            QueryTask.QuerySpecification.QueryOption.BROADCAST,
            QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT))
        .build();

    sendRequest(Operation
        .createPost(this, ServiceUriPaths.CORE_QUERY_TASKS)
        .setBody(containerQueryTask)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                processUpdateMetadataSubStage(currentState, o.getBody(QueryTask.class).results.documents);
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void processUpdateMetadataSubStage(State currentState, Map<String, Object> containerDocuments) {

    OperationJoin
        .create(containerDocuments.values().stream()
            .map((containerDocument) -> Utils.fromJson(containerDocument, ContainerService.State.class))
            .map((containerState) -> Operation.createGet(this, containerState.containerTemplateServiceLink)))
        .setCompletion(
            (ops, exs) -> {
              if (exs != null && !exs.isEmpty()) {
                failTask(exs.values());
                return;
              }

              try {
                updateMetadata(currentState, ops.values().stream()
                    .map((op) -> op.getBody(ContainerTemplateService.State.class))
                    .map(this::getPortMappings)
                    .flatMap((portMap) -> portMap.entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
              } catch (Throwable t) {
                failTask(t);
              }
            })
        .sendWith(this);
  }

  private Map<String, String> getPortMappings(ContainerTemplateService.State templateState) {
    return templateState.portBindings.values().stream()
        .collect(Collectors.toMap(
            (portBinding) -> "CONTAINER_" + portBinding,
            (portBinding) -> templateState.name));
  }

  private void updateMetadata(State currentState, Map<String, String> metadata) throws Throwable {

    VmMetadata vmMetadata = new VmMetadata();
    vmMetadata.setMetadata(metadata);

    HostUtils.getApiClient(this)
        .getVmApi()
        .setMetadataAsync(currentState.vmId, vmMetadata,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processSetMetadataResult(task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processSetMetadataResult(Task task) {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_METADATA_UPDATE, null);
        patchState.updateVmMetadataTaskId = task.getId();
        patchState.updateVmMetadataPollCount = 1;
        sendStageProgressPatch(patchState);
        break;
      case "COMPLETED":
        sendStageProgressPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.ATTACH_ISO);
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // WAIT_FOR_METADATA_UPDATE sub-stage methods
  //

  private void processWaitForMetadataUpdateSubStage(State currentState) throws Throwable {

    HostUtils.getApiClient(this)
        .getTasksApi()
        .getTaskAsync(currentState.updateVmMetadataTaskId,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processMetadataUpdateTaskResult(currentState, task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processMetadataUpdateTaskResult(State currentState, Task task) {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        getHost().schedule(
            () -> {
              State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_METADATA_UPDATE,
                  null);
              patchState.updateVmMetadataPollCount = currentState.updateVmMetadataPollCount + 1;
              TaskUtils.sendSelfPatch(this, patchState);
            },
            currentState.taskPollDelay, TimeUnit.MILLISECONDS);
        break;
      case "COMPLETED":
        sendStageProgressPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.ATTACH_ISO);
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // ATTACH_ISO sub-stage routines
  //

  private void processAttachIsoSubStage(State currentState) {
    if (currentState.serviceConfigDirectory == null) {
      processContainerConfig(currentState);
    } else if (currentState.vmConfigDirectory == null) {
      processConfigIso(currentState);
    } else {
      callAttachIso(currentState);
    }
  }

  private void processContainerConfig(State currentState) {

    QueryTask containerQueryTask = QueryTask.Builder.createDirectTask()
        .setQuery(QueryTask.Query.Builder.create()
            .addKindFieldClause(ContainerService.State.class)
            .addFieldClause(ContainerService.State.FIELD_NAME_VM_SERVICE_LINK, currentState.vmServiceLink)
            .build())
        .addOptions(EnumSet.of(
            QueryTask.QuerySpecification.QueryOption.BROADCAST,
            QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT))
        .build();

    sendRequest(Operation
        .createPost(this, ServiceUriPaths.CORE_QUERY_TASKS)
        .setBody(containerQueryTask)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                processContainerConfig(currentState, o.getBody(QueryTask.class).results.documents);
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void processContainerConfig(State currentState, Map<String, Object> containerDocuments) {

    Map<String, ContainerService.State> containerStates = containerDocuments.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            (entry) -> Utils.fromJson((String) entry.getValue(), ContainerService.State.class)));

    Map<String, Operation> templateOperationMap = containerStates.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            (entry) -> Operation.createGet(this, entry.getValue().containerTemplateServiceLink)));

    OperationJoin
        .create(templateOperationMap.values())
        .setCompletion(
            (ops, exs) -> {
              if (exs != null && !exs.isEmpty()) {
                failTask(exs.values());
                return;
              }

              try {
                processContainerConfig(currentState, containerStates, templateOperationMap.keySet().stream()
                    .collect(Collectors.toMap(
                        (containerServiceLink) -> containerServiceLink,
                        (containerServiceLink) -> ops.get(templateOperationMap.get(containerServiceLink).getId())
                            .getBody(ContainerTemplateService.State.class))));
              } catch (Throwable t) {
                failTask(t);
              }
            })
        .sendWith(this);
  }

  private void processContainerConfig(State currentState,
                                      Map<String, ContainerService.State> containerStates,
                                      Map<String, ContainerTemplateService.State> templateStates) throws Throwable {

    Path serviceConfigDirectoryPath = Files.createTempDirectory("mustache-" + currentState.vmId).toAbsolutePath();
    ServiceUtils.logInfo(this, "Created service config directory: " + serviceConfigDirectoryPath.toString());
    ServiceConfigurator serviceConfigurator = HostUtils.getServiceConfiguratorFactory(this).create();
    serviceConfigurator.copyDirectory(HostUtils.getDeployerContext(this).getConfigDirectory(),
        serviceConfigDirectoryPath.toString());

    for (ContainerService.State containerState : containerStates.values()) {
      Map<String, Object> dynamicParameters = new HashMap<>();
      if (containerState.dynamicParameters != null) {
        dynamicParameters.putAll(containerState.dynamicParameters);
      }

      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_HAPROXY_MGMT_API_HTTP_SERVERS,
          (k, v) -> new Gson().fromJson(v.toString(), loadBalancerTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_HAPROXY_MGMT_UI_HTTP_SERVERS,
          (k, v) -> new Gson().fromJson(v.toString(), loadBalancerTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_HAPROXY_MGMT_UI_HTTPS_SERVERS,
          (k, v) -> new Gson().fromJson(v.toString(), loadBalancerTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_COMMON_PEER_NODES,
          (k, v) -> new Gson().fromJson(v.toString(), peerNodeTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_CLOUDSTORE_PEER_NODES,
          (k, v) -> new Gson().fromJson(v.toString(), peerNodeTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_HOUSEKEEPER_PEER_NODES,
          (k, v) -> new Gson().fromJson(v.toString(), peerNodeTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_SCHEDULER_PEER_NODES,
          (k, v) -> new Gson().fromJson(v.toString(), peerNodeTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildRuntimeConfigurationTaskService.MUSTACHE_KEY_DEPLOYER_PEER_NODES,
          (k, v) -> new Gson().fromJson(v.toString(), peerNodeTypeToken.getType()));
      dynamicParameters.computeIfPresent(
          BuildContainersConfigurationWorkflowService.MUSTACHE_KEY_ZOOKEEPER_INSTANCES,
          (k, v) -> new Gson().fromJson(v.toString(), zookeeperTypeToken.getType()));

      serviceConfigurator.applyDynamicParameters(serviceConfigDirectoryPath.toString(),
          ContainersConfig.ContainerType.valueOf(templateStates.get(containerState.documentSelfLink).name),
          dynamicParameters);
    }

    State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.ATTACH_ISO, null);
    patchState.serviceConfigDirectory = serviceConfigDirectoryPath.toString();
    TaskUtils.sendSelfPatch(this, patchState);
  }

  private void processConfigIso(State currentState) {

    sendRequest(Operation
        .createGet(this, currentState.vmServiceLink)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                processConfigIso(currentState, o.getBody(VmService.State.class));
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void processConfigIso(State currentState, VmService.State vmState) {

    sendRequest(HostUtils.getCloudStoreHelper(this)
        .createGet(vmState.hostServiceLink)
        .setCompletion(
            (o, e) -> {
              if (e != null) {
                failTask(e);
                return;
              }

              try {
                processConfigIso(currentState, vmState, o.getBody(HostService.State.class));
              } catch (Throwable t) {
                failTask(t);
              }
            }));
  }

  private void processConfigIso(State currentState,
                                VmService.State vmState,
                                HostService.State hostState)
      throws Throwable {

    checkState(hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_GATEWAY));
    checkState(hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_IP));
    checkState(hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_NETMASK));
    checkState(hostState.metadata.containsKey(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_DNS_SERVER));

    String gateway = hostState.metadata.get(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_GATEWAY);
    String ipAddress = hostState.metadata.get(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_IP);
    String netmask = hostState.metadata.get(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_NETMASK);
    String dnsEndpointList = hostState.metadata.get(HostService.State.METADATA_KEY_NAME_MANAGEMENT_NETWORK_DNS_SERVER);
    if (!Strings.isNullOrEmpty(dnsEndpointList)) {
      dnsEndpointList = Stream.of(dnsEndpointList.split(","))
          .map((dnsServer) -> "DNS=" + dnsServer + "\n")
          .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append).toString();
    }

    DeployerContext deployerContext = HostUtils.getDeployerContext(this);
    String scriptDirectory = deployerContext.getScriptDirectory();

    String userDataConfigFileContent =
        new String(Files.readAllBytes(Paths.get(scriptDirectory, "user-data.template")), StandardCharsets.UTF_8)
            .replace("$GATEWAY", gateway)
            .replace("$ADDRESS", new SubnetUtils(ipAddress, netmask).getInfo().getCidrSignature())
            .replace("$DNS", dnsEndpointList);

    if (currentState.ntpEndpoint != null) {
      userDataConfigFileContent = userDataConfigFileContent.replace("$NTP", currentState.ntpEndpoint);
    }

    String metadataConfigFileContent =
        new String(Files.readAllBytes(Paths.get(scriptDirectory, "meta-data.template")), StandardCharsets.UTF_8)
            .replace("$INSTANCE_ID", vmState.name)
            .replace("$LOCAL_HOSTNAME", vmState.name);

    Path vmConfigDirectoryPath = Files.createTempDirectory("iso-" + currentState.vmId).toAbsolutePath();
    Path userDataConfigFilePath = vmConfigDirectoryPath.resolve("user-data.yml");
    Files.write(userDataConfigFilePath, userDataConfigFileContent.getBytes(StandardCharsets.UTF_8));
    Path metadataConfigFilePath = vmConfigDirectoryPath.resolve("meta-data.yml");
    Files.write(metadataConfigFilePath, metadataConfigFileContent.getBytes(StandardCharsets.UTF_8));
    Path isoFilePath = vmConfigDirectoryPath.resolve("config.iso");

    List<String> command = new ArrayList<>();
    command.add("./" + SCRIPT_NAME);
    command.add(isoFilePath.toAbsolutePath().toString());
    command.add(userDataConfigFilePath.toAbsolutePath().toString());
    command.add(metadataConfigFilePath.toAbsolutePath().toString());
    command.add(currentState.serviceConfigDirectory);

    File scriptLogFile = new File(deployerContext.getScriptLogDirectory(), SCRIPT_NAME + "-" + vmState.vmId + "-" +
        ServiceUtils.getIDFromDocumentSelfLink(currentState.documentSelfLink) + ".log");

    ScriptRunner scriptRunner = new ScriptRunner.Builder(command, deployerContext.getScriptTimeoutSec())
        .directory(deployerContext.getScriptDirectory())
        .redirectOutput(ProcessBuilder.Redirect.to(scriptLogFile))
        .build();

    ListenableFutureTask<Integer> futureTask = ListenableFutureTask.create(scriptRunner);
    HostUtils.getListeningExecutorService(this).submit(futureTask);
    Futures.addCallback(futureTask,
        new FutureCallback<Integer>() {
          @Override
          public void onSuccess(@javax.validation.constraints.NotNull Integer result) {
            try {
              if (result != 0) {
                logScriptErrorAndFail(currentState, result, scriptLogFile);
              } else {
                State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.ATTACH_ISO, null);
                patchState.vmConfigDirectory = vmConfigDirectoryPath.toAbsolutePath().toString();
                TaskUtils.sendSelfPatch(CreateDhcpVmTaskService.this, patchState);
              }
            } catch (Throwable t) {
              failTask(t);
            }
          }

          @Override
          public void onFailure(Throwable throwable) {
            failTask(throwable);
          }
        });
  }

  private void logScriptErrorAndFail(State currentState, Integer result, File scriptLogFile) throws Throwable {
    ServiceUtils.logSevere(this, SCRIPT_NAME + " returned " + result.toString());
    ServiceUtils.logSevere(this, "Script output: " + FileUtils.readFileToString(scriptLogFile));
    failTask(new IllegalStateException("Creating the configuration ISO for VM " + currentState.vmId +
        " failed with exit code " + result.toString()));
  }

  private void callAttachIso(State currentState) {

    //
    // N.B. The uploadAndAttachIso call is performed on a separate thread because it is blocking.
    // This behavior can be removed if and when an asynchronous version of this call is added to
    // the Java API client library.
    //

    ListenableFutureTask<Task> futureTask = ListenableFutureTask.create(
        () -> HostUtils.getApiClient(this)
            .getVmApi()
            .uploadAndAttachIso(currentState.vmId,
                Paths.get(currentState.vmConfigDirectory, "config.iso").toAbsolutePath().toString()));

    HostUtils.getListeningExecutorService(this).submit(futureTask);
    Futures.addCallback(futureTask,
        new FutureCallback<Task>() {
          @Override
          public void onSuccess(@javax.validation.constraints.NotNull Task task) {
            try {
              processAttachIsoTaskResult(currentState, task);
            } catch (Throwable t) {
              failTask(t);
            }
          }

          @Override
          public void onFailure(Throwable throwable) {
            failTask(throwable);
          }
        });
  }

  private void processAttachIsoTaskResult(State currentState, Task task) throws Throwable {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_ATTACH_ISO, null);
        patchState.attachIsoTaskId = task.getId();
        patchState.attachIsoPollCount = 1;
        sendStageProgressPatch(patchState);
        break;
      case "COMPLETED":
        FileUtils.deleteDirectory(Paths.get(currentState.serviceConfigDirectory).toFile());
        FileUtils.deleteDirectory(Paths.get(currentState.vmConfigDirectory).toFile());
        sendStageProgressPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.START_VM);
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // WAIT_FOR_ATTACH_ISO sub-stage routines
  //

  private void processWaitForAttachIsoSubStage(State currentState) throws Throwable {

    HostUtils.getApiClient(this)
        .getTasksApi()
        .getTaskAsync(currentState.attachIsoTaskId,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processWaitForAttachIsoSubStage(currentState, task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processWaitForAttachIsoSubStage(State currentState, Task task) throws Throwable {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        getHost().schedule(
            () -> {
              State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_ATTACH_ISO, null);
              patchState.attachIsoPollCount = currentState.attachIsoPollCount + 1;
              TaskUtils.sendSelfPatch(this, patchState);
            },
            currentState.taskPollDelay, TimeUnit.MILLISECONDS);
        break;
      case "COMPLETED":
        FileUtils.deleteDirectory(Paths.get(currentState.serviceConfigDirectory).toFile());
        FileUtils.deleteDirectory(Paths.get(currentState.vmConfigDirectory).toFile());
        sendStageProgressPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.START_VM);
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // START_VM sub-stage routines
  //

  private void processStartVmSubStage(State currentState) throws Throwable {

    HostUtils.getApiClient(this)
        .getVmApi()
        .performStartOperationAsync(currentState.vmId,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processPerformStartOperationTaskResult(currentState, task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processPerformStartOperationTaskResult(State currentState, Task task) {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        getHost().schedule(
            () -> {
              State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_VM_START, null);
              patchState.startVmTaskId = task.getId();
              patchState.startVmPollCount = 1;
              sendStageProgressPatch(patchState);
            },
            currentState.taskPollDelay, TimeUnit.MILLISECONDS);
        break;
      case "COMPLETED":
        State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_DHCP_AGENT, null);
        patchState.dhcpAgentPollIterations = 1;
        sendStageProgressPatch(patchState);
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // WAIT_FOR_VM_START sub-stage routines
  //

  private void processWaitForVmStartSubStage(State currentState) throws Throwable {

    HostUtils.getApiClient(this)
        .getTasksApi()
        .getTaskAsync(currentState.startVmTaskId,
            new FutureCallback<Task>() {
              @Override
              public void onSuccess(@javax.validation.constraints.NotNull Task task) {
                try {
                  processWaitForVmStartTaskResult(currentState, task);
                } catch (Throwable t) {
                  failTask(t);
                }
              }

              @Override
              public void onFailure(Throwable throwable) {
                failTask(throwable);
              }
            });
  }

  private void processWaitForVmStartTaskResult(State currentState, Task task) {
    switch (task.getState().toUpperCase()) {
      case "QUEUED":
      case "STARTED":
        getHost().schedule(
            () -> {
              State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_VM_START, null);
              patchState.startVmPollCount = currentState.startVmPollCount + 1;
              TaskUtils.sendSelfPatch(this, patchState);
            },
            currentState.taskPollDelay, TimeUnit.MILLISECONDS);
        break;
      case "COMPLETED":
        State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_DHCP_AGENT, null);
        patchState.dhcpAgentPollIterations = 1;
        sendStageProgressPatch(patchState);
        break;
      case "ERROR":
        throw new IllegalStateException(ApiUtils.getErrors(task));
      default:
        throw new IllegalStateException("Unknown task state: " + task.getState());
    }
  }

  //
  // WAIT_FOR_DHCP_AGENT sub-stage routines
  //

  private void processWaitForDhcpAgentSubStage(State currentState) {

    if (currentState.dhcpAgentEndpointAddress == null) {
      sendRequest(Operation
          .createGet(this, currentState.vmServiceLink)
          .setCompletion(
              (o, e) -> {
                if (e != null) {
                  failTask(e);
                  return;
                }

                try {
                  State patchState = buildPatch(
                      TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_DHCP_AGENT, null);
                  patchState.dhcpAgentEndpointAddress = o.getBody(VmService.State.class).ipAddress;
                  TaskUtils.sendSelfPatch(this, patchState);
                } catch (Throwable t) {
                  failTask(t);
                }
              }));

      return;
    }

    final HealthChecker healthChecker = HostUtils.getHealthCheckHelperFactory(this)
        .create(this, Constants.DHCP_AGENT_PORT, currentState.dhcpAgentEndpointAddress);

    if (healthChecker.isReady()) {
      ServiceUtils.logInfo(this,
          "Received READY status response, dhcpAgentEndpointAddress: " + currentState.dhcpAgentEndpointAddress);
      sendStageProgressPatch(TaskState.TaskStage.FINISHED, null);
    } else {
      processFailedDhcpAgentPollingInterval(currentState);
    }
  }

  private void processFailedDhcpAgentPollingInterval(State currentState) {
    if (currentState.dhcpAgentPollIterations >= currentState.maxDhcpAgentPollIterations) {
      failTask(new IllegalStateException("The DHCP Agent endpoint on VM " + currentState.dhcpAgentEndpointAddress +
          " failed to become ready after " + currentState.dhcpAgentPollIterations + " polling iterations"));
    } else {
      getHost().schedule(
          () -> {
            State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.WAIT_FOR_DHCP_AGENT, null);
            patchState.dhcpAgentPollIterations = currentState.dhcpAgentPollIterations + 1;
            TaskUtils.sendSelfPatch(this, patchState);
          },
          currentState.taskPollDelay, TimeUnit.MILLISECONDS);
    }
  }

  //
  // Utility routines
  //

  private void sendStageProgressPatch(TaskState.TaskStage taskStage, TaskState.SubStage subStage) {
    sendStageProgressPatch(buildPatch(taskStage, subStage, null));
  }

  private void sendStageProgressPatch(State patchState) {
    ServiceUtils.logTrace(this, "Sending self-patch to stage %s:%s", patchState.taskState.stage,
        patchState.taskState.subStage);
    TaskUtils.sendSelfPatch(this, patchState);
  }

  private void failTask(Throwable failure) {
    ServiceUtils.logSevere(this, failure);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failure));
  }

  private void failTask(Collection<Throwable> failures) {
    ServiceUtils.logSevere(this, failures);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failures.iterator().next()));
  }

  @VisibleForTesting
  protected static State buildPatch(TaskState.TaskStage taskStage,
                                    TaskState.SubStage subStage,
                                    @Nullable Throwable failure) {
    State patchState = new State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = taskStage;
    patchState.taskState.subStage = subStage;
    if (failure != null) {
      patchState.taskState.failure = Utils.toServiceErrorResponse(failure);
    }

    return patchState;
  }
}
