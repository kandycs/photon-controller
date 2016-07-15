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

package com.vmware.photon.controller.apife.exceptions.external;

import com.vmware.photon.controller.api.common.exceptions.external.ErrorCode;
import com.vmware.photon.controller.api.common.exceptions.external.ExternalException;

/**
 * Gets thrown when a required project lookup by id is needed and the project is
 * not accessible.
 */
public class ProjectNotFoundException extends ExternalException {

  private final String projectId;

  public ProjectNotFoundException(String projectId) {
    super(ErrorCode.NOT_FOUND);

    this.projectId = projectId;

    addData("id", projectId);
  }

  @Override
  public String getMessage() {
    return String.format("Project %s not found", projectId);
  }
}
