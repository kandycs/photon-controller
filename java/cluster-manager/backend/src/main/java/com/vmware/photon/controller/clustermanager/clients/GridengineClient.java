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

package com.vmware.photon.controller.clustermanager.clients;

import com.google.common.util.concurrent.FutureCallback;
//import com.vmware.photon.controller.client.RestClient;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;

import java.io.IOException;

/*
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
*/

/*
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
*/

/**
 * copied from KubernetesClient.java, which was used to represent a simple REST
 * client to call into Kubernetes Rest APIs to query the status of the cluster.
 * However, grid engine has now such interface. what can we do here
 */
public class GridengineClient {
  public GridengineClient(CloseableHttpAsyncClient httpClient) {
  }

  public void checkStatus(final String connectionString, final FutureCallback<Boolean> callback) throws IOException {
    // empty not implemented yet
  }
}
