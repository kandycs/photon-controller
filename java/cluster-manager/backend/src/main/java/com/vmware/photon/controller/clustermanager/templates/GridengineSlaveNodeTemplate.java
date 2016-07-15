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

package com.vmware.photon.controller.clustermanager.templates;

import com.vmware.photon.controller.clustermanager.servicedocuments.ClusterManagerConstants;
import com.vmware.photon.controller.clustermanager.servicedocuments.FileTemplate;

import com.google.common.base.Preconditions;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the template for GridEngine Slave Nodes.
 */
public class GridengineSlaveNodeTemplate implements NodeTemplate {

  public static final String SLAVE_USER_DATA_TEMPLATE = "sge-slave-user-data.template";
  public static final String NFS_IP_PROPERTY = "nfsIp";
  public static final String MASTER_IP_PROPERTY = "masterIp";
  public static final String VM_NAME_PREFIX = "slave";

  public String getVmName(Map<String, String> properties) {
    Preconditions.checkNotNull(properties, "properties cannot be null");

    String hostId = properties.get(NodeTemplateUtils.HOST_ID_PROPERTY);
    return NodeTemplateUtils.generateHostName(VM_NAME_PREFIX, hostId);
  }

  public FileTemplate createUserDataTemplate(String scriptDirectory, Map<String, String> properties) {
    Preconditions.checkNotNull(scriptDirectory, "scriptDirectory cannot be null");
    Preconditions.checkNotNull(properties, "properties cannot be null");

    String nodeIndexStr = properties.get(NodeTemplateUtils.NODE_INDEX_PROPERTY);
    int nodeIndex = Integer.parseInt(nodeIndexStr);

    Map<String, String> parameters = new HashMap<>();
    parameters.put("$NFS_IP", properties.get(NFS_IP_PROPERTY));
    parameters.put("$KUBERNETES_PORT", String.valueOf(ClusterManagerConstants.Kubernetes.API_PORT));
    parameters.put("$MASTER_ADDRESS", properties.get(MASTER_IP_PROPERTY));
    parameters.put("$SGE_ID", Integer.toString(nodeIndex));
    parameters.put("$SSH_PORT", String.valueOf(ClusterManagerConstants.Gridengine.SSH_PORT));
    parameters.put("$QMASTER_PORT", String.valueOf(ClusterManagerConstants.Gridengine.QMASTER_PORT));
    parameters.put("$EXECD_PORT", String.valueOf(ClusterManagerConstants.Gridengine.EXECD_PORT));
    parameters.put("$LOCAL_HOSTNAME", getVmName(properties));

    FileTemplate template = new FileTemplate();
    template.filePath = Paths.get(scriptDirectory, SLAVE_USER_DATA_TEMPLATE).toString();
    template.parameters = parameters;
    return template;
  }

  public FileTemplate createMetaDataTemplate(String scriptDirectory, Map<String, String> properties) {
    Preconditions.checkNotNull(scriptDirectory, "scriptDirectory cannot be null");
    Preconditions.checkNotNull(properties, "properties cannot be null");

    return NodeTemplateUtils.createMetaDataTemplate(scriptDirectory, getVmName(properties));
  }

  public static Map<String, String> createProperties(String nfsAddress, String masterIp) {
    Preconditions.checkNotNull(nfsAddress, "nfsAddress cannot be null");
    Preconditions.checkNotNull(masterIp, "masterIp cannot be null");

    Map<String, String> properties = new HashMap<>();
    properties.put(NFS_IP_PROPERTY, nfsAddress);
    properties.put(MASTER_IP_PROPERTY, masterIp);
    return properties;
  }
}
