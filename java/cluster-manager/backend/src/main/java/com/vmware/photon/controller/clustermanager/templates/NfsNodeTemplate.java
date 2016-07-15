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

import com.vmware.photon.controller.clustermanager.servicedocuments.FileTemplate;

import com.google.common.base.Preconditions;
import org.apache.commons.net.util.SubnetUtils;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the template for Etcd Nodes.
 */
public class NfsNodeTemplate implements NodeTemplate {

  public static final String NFS_USER_DATA_TEMPLATE = "sge-storage-user-data.template";
  public static final String DNS_PROPERTY = "dns";
  public static final String GATEWAY_PROPERTY = "gateway";
  public static final String NETMASK_PROPERTY = "netmask";
  public static final String NFS_IPS_PROPERTY = "nfsIp";
  public static final String VM_NAME_PREFIX = "nfs";

  public String getVmName(Map<String, String> properties) {
    Preconditions.checkNotNull(properties, "properties cannot be null");

    String hostId = properties.get(NodeTemplateUtils.HOST_ID_PROPERTY);
    return NodeTemplateUtils.generateHostName(VM_NAME_PREFIX, hostId);
  }

  public FileTemplate createUserDataTemplate(String scriptDirectory, Map<String, String> properties) {
    Preconditions.checkNotNull(scriptDirectory, "scriptDirectory cannot be null");
    Preconditions.checkNotNull(properties, "properties cannot be null");

    String dns = properties.get(DNS_PROPERTY);
    String gateway = properties.get(GATEWAY_PROPERTY);
    String netmask = properties.get(NETMASK_PROPERTY);
    String ipAddress = properties.get(NFS_IPS_PROPERTY);
    String cidrSignature = new SubnetUtils(ipAddress, netmask).getInfo().getCidrSignature();

    Map<String, String> parameters = new HashMap();
    parameters.put("$DNS", "DNS=" + dns);
    parameters.put("$GATEWAY", gateway);
    parameters.put("$ADDRESS", cidrSignature);

    FileTemplate template = new FileTemplate();
    template.filePath = Paths.get(scriptDirectory, NFS_USER_DATA_TEMPLATE).toString();
    template.parameters = parameters;
    return template;
  }

  public FileTemplate createMetaDataTemplate(String scriptDirectory, Map<String, String> properties) {
    Preconditions.checkNotNull(scriptDirectory, "scriptDirectory cannot be null");
    Preconditions.checkNotNull(properties, "properties cannot be null");

    return NodeTemplateUtils.createMetaDataTemplate(scriptDirectory, getVmName(properties));
  }

  public static Map<String, String> createProperties(String dns, String gateway, String netmask, String nfsAddress) {

    Preconditions.checkNotNull(dns, "dns cannot be null");
    Preconditions.checkNotNull(gateway, "gateway cannot be null");
    Preconditions.checkNotNull(netmask, "netmask cannot be null");
    Preconditions.checkNotNull(nfsAddress, "nfsAddress cannot be null");

    Map<String, String> properties = new HashMap();
    properties.put(DNS_PROPERTY, dns);
    properties.put(GATEWAY_PROPERTY, gateway);
    properties.put(NETMASK_PROPERTY, netmask);
    properties.put(NFS_IPS_PROPERTY, nfsAddress);

    return properties;
  }
}
