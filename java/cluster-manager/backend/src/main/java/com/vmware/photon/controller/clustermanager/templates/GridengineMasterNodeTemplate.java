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
import org.apache.commons.net.util.SubnetUtils;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the template for GridEngine Master Nodes.
 */
public class GridengineMasterNodeTemplate implements NodeTemplate {

  public static final String MASTER_USER_DATA_TEMPLATE = "sge-master-user-data.template";
  public static final String NFS_IP_PROPERTY = "nfsIp";
  public static final String DNS_PROPERTY = "dns";
  public static final String GATEWAY_PROPERTY = "gateway";
  public static final String MASTER_IP_PROPERTY = "masterIp";
  public static final String NETMASK_PROPERTY = "netmask";
  public static final String VM_NAME_PREFIX = "sgemaster";

  public String getVmName(Map<String, String> properties) {
    Preconditions.checkNotNull(properties, "properties cannot be null");

    String hostId = properties.get(NodeTemplateUtils.HOST_ID_PROPERTY);
    return NodeTemplateUtils.generateHostName(VM_NAME_PREFIX, hostId);
  }

  public FileTemplate createUserDataTemplate(String scriptDirectory, Map<String, String> properties) {
    Preconditions.checkNotNull(scriptDirectory, "scriptDirectory cannot be null");
    Preconditions.checkNotNull(properties, "properties cannot be null");

    String nfsIp = properties.get(NFS_IP_PROPERTY);
    String ipAddress = properties.get(MASTER_IP_PROPERTY);
    String netmask = properties.get(NETMASK_PROPERTY);
    String cidrSignature = new SubnetUtils(ipAddress, netmask).getInfo().getCidrSignature();

    Map<String, String> parameters = new HashMap<>();
    parameters.put("$NFS_IP", nfsIp);
    parameters.put("$DNS", "DNS=" + properties.get(DNS_PROPERTY));
    parameters.put("$GATEWAY", properties.get(GATEWAY_PROPERTY));
    parameters.put("$ADDRESS", cidrSignature);
    parameters.put("$SSH_PORT", String.valueOf(ClusterManagerConstants.Gridengine.SSH_PORT));
    parameters.put("$QMASTER_PORT", String.valueOf(ClusterManagerConstants.Gridengine.QMASTER_PORT));
    parameters.put("$EXECD_PORT", String.valueOf(ClusterManagerConstants.Gridengine.EXECD_PORT));
    parameters.put("$LOCAL_HOSTNAME", getVmName(properties));

    FileTemplate template = new FileTemplate();
    template.filePath = Paths.get(scriptDirectory, MASTER_USER_DATA_TEMPLATE).toString();
    template.parameters = parameters;
    return template;
  }

  public FileTemplate createMetaDataTemplate(String scriptDirectory, Map<String, String> properties) {
    Preconditions.checkNotNull(scriptDirectory, "scriptDirectory cannot be null");
    Preconditions.checkNotNull(properties, "properties cannot be null");

    return NodeTemplateUtils.createMetaDataTemplate(scriptDirectory, getVmName(properties));
  }

  public static Map<String, String> createProperties(String nfsAddress, String dns, String gateway, String netmask,
      String masterIp) {
    Preconditions.checkNotNull(nfsAddress, "nfsAddress cannot be null");
    Preconditions.checkNotNull(dns, "dns cannot be null");
    Preconditions.checkNotNull(gateway, "gateway cannot be null");
    Preconditions.checkNotNull(masterIp, "masterIp cannot be null");
    Preconditions.checkNotNull(netmask, "netmask cannot be null");

    Map<String, String> properties = new HashMap<>();
    properties.put(NFS_IP_PROPERTY, nfsAddress);
    properties.put(DNS_PROPERTY, dns);
    properties.put(GATEWAY_PROPERTY, gateway);
    properties.put(MASTER_IP_PROPERTY, masterIp);
    properties.put(NETMASK_PROPERTY, netmask);

    return properties;
  }
}
