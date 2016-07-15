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

package com.vmware.photon.controller.clustermanager.statuschecks;

import com.vmware.photon.controller.clustermanager.clients.GridengineClient;
import com.vmware.photon.controller.clustermanager.servicedocuments.ClusterManagerConstants;

import com.google.common.util.concurrent.FutureCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Set;



/**
 * Determines the Status of a Etcd Node.
 */
public class GridengineStatusChecker implements StatusChecker, SlavesStatusChecker {

  private static final Logger logger = LoggerFactory.getLogger(GridengineStatusChecker.class);
  private GridengineClient gridengineClient;

  public GridengineStatusChecker(GridengineClient gridengineClient) {
    this.gridengineClient = gridengineClient;
  }

  @Override
  public void checkNodeStatus(final String nodeAddress, final FutureCallback<Boolean> callback) {
    logger.info("Checking Etcd: {}", nodeAddress);

    try {
      String connectionString = createConnectionString(nodeAddress);
      gridengineClient.checkStatus(connectionString, new FutureCallback<Boolean>() {
        @Override
        public void onSuccess(@Nullable Boolean isReady) {
          try {
            callback.onSuccess(isReady);
          } catch (Throwable t) {
            logger.warn("Etcd call failed: ", t);
            callback.onFailure(t);
          }
        }

        @Override
        public void onFailure(Throwable t) {
          logger.warn("Etcd call failed: ", t);
          callback.onSuccess(false);
        }
      });
    } catch (Exception e) {
      logger.warn("Etcd call failed: ", e);
      callback.onSuccess(false);
    }
  }

  @Override
  public void checkSlavesStatus(final String masterAddress, final List<String> slaveAddresses,
      final FutureCallback<Boolean> callback) {
    // not implemented
  }

  @Override
  public void getSlavesStatus(String serverAddress, final FutureCallback<Set<String>> callback) {
    // not implemented
  }

  private static String createConnectionString(String serverAddress) {
    return "http://" + serverAddress + ":" + ClusterManagerConstants.Swarm.ETCD_PORT;
  }
}
