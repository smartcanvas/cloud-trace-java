// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.trace.sdk.servlet;

import com.google.cloud.trace.sdk.CloudTraceException;
import com.google.cloud.trace.sdk.TraceContext;
import com.google.cloud.trace.sdk.TraceSpanData;
import com.google.cloud.trace.sdk.TraceSpanLabel;
import com.google.cloud.trace.sdk.TraceWriter;

import javax.servlet.http.HttpServletResponse;

/**
 * Utilities for working with {@link TraceSpanData}s in the context of servlet responses.
 */
public class TraceResponseUtils {
  /**
   * Sets the given context into the given servlet response.
   * @throws CloudTraceException
   */
  public void closeResponseSpanData(TraceSpanData spanData, TraceWriter writer,
      HttpServletResponse response) throws CloudTraceException {
    // Set the trace context on the response.
    response.setHeader(TraceContext.TRACE_HEADER, spanData.getContext().toTraceHeader());

    // Fill in a standard label on the span.
    TraceSpanLabel httpResponseLabel = new TraceSpanLabel(
        HttpServletSpanLabels.HTTP_RESPONSE_CODE_LABEL_KEY, "" + response.getStatus());
    spanData.addLabel(httpResponseLabel);
    spanData.end();
    writer.writeSpan(spanData);
  }
}
