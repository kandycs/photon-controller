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

package com.vmware.photon.controller.api.constraints;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.validation.ConstraintValidatorContext;

import java.net.UnknownHostException;

/**
 * Test {@link NullableUrlValidator}.
 */
public class NullableUrlValidatorTest {
  @Mock
  private ConstraintValidatorContext context;
  @Mock
  private ConstraintValidatorContext.ConstraintViolationBuilder builder;

  private NullableUrlValidator validator;

  @BeforeMethod
  public void setup() {
    MockitoAnnotations.initMocks(this);
    Mockito.doReturn(builder).when(context).buildConstraintViolationWithTemplate(Mockito.anyString());

    validator = new NullableUrlValidator();
  }

  @Test
  public void testNull() {
    Assert.assertTrue(validator.isValid(null, context));
  }

  @Test
  public void testValidUrl() throws UnknownHostException {
    Assert.assertTrue(validator.isValid("https://10.146.39.198:7444/lookupservice/sdk", context));
  }

  @Test
  public void testInvalidHostName() {
    Assert.assertFalse(validator.isValid("fakename", context));
  }
}
