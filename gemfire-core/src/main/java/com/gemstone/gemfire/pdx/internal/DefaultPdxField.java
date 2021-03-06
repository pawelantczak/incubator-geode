/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gemstone.gemfire.pdx.internal;

import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.tcp.ByteBufferInputStream.ByteSource;
import com.gemstone.gemfire.internal.tcp.ByteBufferInputStream.ByteSourceFactory;

/**
 * Used by {@link PdxInstanceImpl#equals(Object)} to act as if it has
 * a field whose value is always the default.
 * @author darrel
 *
 */
public class DefaultPdxField extends PdxField {

  public DefaultPdxField(PdxField f) {
    super(f);
  }

  public ByteSource getDefaultBytes() {
    return ByteSourceFactory.create(getFieldType().getDefaultBytes());
  }

}
