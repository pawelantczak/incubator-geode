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
   
   
package com.gemstone.gemfire.internal.admin.remote;

import com.gemstone.gemfire.distributed.internal.*;
//import com.gemstone.gemfire.*;
//import com.gemstone.gemfire.internal.*;
import java.io.*;
//import java.util.*;
import com.gemstone.gemfire.distributed.internal.membership.*;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * A message that is sent as a reply to a {@link AdminRequest}.
 */
public abstract class AdminResponse extends HighPriorityDistributionMessage
  implements AdminMessageType {

  // instance variables

  private int msgId; // message id of request this is a response to

  // instance methods

  int getMsgId() {
    return this.msgId;
  }

  void setMsgId(int msgId) {
    this.msgId = msgId;
  }
  
  @Override
  public boolean sendViaUDP() {
    return true;
  }

  /**
   * This method is invoked on the side that sent the original AdminRequest.
   */
  @Override
  protected void process(DistributionManager dm) {
    AdminWaiters.sendResponse(this);
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    //System.out.println("BEGIN AdminResponse toData");
    super.toData(out);
    out.writeInt(this.msgId);
    //System.out.println("END AdminResponse toData");
  }

  @Override
  public void fromData(DataInput in)
    throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.msgId = in.readInt();
  }

  public InternalDistributedMember getRecipient() {
    InternalDistributedMember[] recipients = getRecipients();
    int size = recipients.length;
    if (size == 0) {
      return null;
    } else if (size > 1) {
      throw new
        IllegalStateException(
          LocalizedStrings.AdminResponse_COULD_NOT_RETURN_ONE_RECIPIENT_BECAUSE_THIS_MESSAGE_HAS_0_RECIPIENTS.toLocalizedString(Integer.valueOf(size)));
    } else {
      return recipients[0];
    }
  }
}
