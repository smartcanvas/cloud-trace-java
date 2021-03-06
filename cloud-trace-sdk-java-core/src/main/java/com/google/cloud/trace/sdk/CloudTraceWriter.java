// Copyright 2015 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.trace.sdk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.trace.api.v1.model.Trace;
import com.google.cloud.trace.api.v1.model.TraceSpan;
import com.google.cloud.trace.api.v1.model.Traces;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

/**
 * Writes traces to the public Google Cloud Trace API.
 * Aggregates trace spans by trace id. If any span on the trace has shouldWrite == true,
 * then the trace will be pushed to the API server. Otherwise, it will be dropped at
 * this point.
 */
public class CloudTraceWriter implements TraceWriter, CanInitFromProperties {

  private static final Logger logger = Logger.getLogger(CloudTraceWriter.class.getName());

  /**
   * The scope(s) we need to write traces to the Cloud Trace API.
   */
  public static List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/trace.append");

  /**
   * Request factory for calling the Cloud Trace API.
   */
  private CloudTraceRequestFactory requestFactory;

  /**
   * The id of the cloud project to write traces to.
   */
  private String projectId;

  /**
   * The endpoint of the Google API service to call.
   */
  private String apiEndpoint = "https://cloudtrace.googleapis.com/";

  /**
   * JSON mapper for forming API requests.
   */
  private ObjectMapper objectMapper;

  public CloudTraceWriter() {
    this.objectMapper = new ObjectMapper();
  }

  @Inject
  public CloudTraceWriter(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
  }
  
  public CloudTraceRequestFactory getRequestFactory() {
    return requestFactory;
  }

  public void setRequestFactory(CloudTraceRequestFactory requestFactory) {
    this.requestFactory = requestFactory;
  }

  public String getProjectId() {
    return projectId;
  }

  public void setProjectId(String projectId) {
    this.projectId = projectId;
  }

  public String getApiEndpoint() {
    return apiEndpoint;
  }

  public void setApiEndpoint(String apiEndpoint) {
    this.apiEndpoint = apiEndpoint;
  }

  @Override
  public void initFromProperties(Properties props) throws CloudTraceException {
    this.projectId = props.getProperty(getClass().getName() + ".projectId");
    String apiEndpointProp = props.getProperty(getClass().getName() + ".apiEndpoint");
    if (apiEndpointProp != null && !apiEndpointProp.isEmpty()) {
      this.apiEndpoint = apiEndpointProp;
    }
    String requestFactoryClassName = props.getProperty(getClass().getName() + ".requestFactory");
    if (requestFactoryClassName != null && !requestFactoryClassName.isEmpty()) {
      this.requestFactory = (CloudTraceRequestFactory) ReflectionUtils.createFromProperties(
          requestFactoryClassName, props);
    } else {
      this.requestFactory = new HttpTransportCloudTraceRequestFactory();      
    }
    requestFactory.initFromProperties(props);
  }

  @Override
  public void writeSpan(TraceSpanData span) throws CloudTraceException {
    checkState();
    span.end();
    if (!span.getContext().getShouldWrite()) {
      return;
    }
    Trace trace = convertTraceSpanDataToTrace(span);
    writeTraces(new Traces().setTraces(ImmutableList.of(trace)));
  }

  @Override
  public void writeSpans(List<TraceSpanData> spans) throws CloudTraceException {
    // Aggregate all the spans by trace. It's more efficient to call the API this way.
    Map<String, Trace> traces = new HashMap<>();
    
    // Keep track of traces we really want to write out.
    Set<String> shouldWriteTraces = new HashSet<>();
    
    for (TraceSpanData spanData : spans) {
      spanData.end();
      TraceSpan span = convertTraceSpanDataToSpan(spanData);
      if (spanData.getContext().getShouldWrite()) {
        shouldWriteTraces.add(spanData.getContext().getTraceId());
      }
      
      if (!traces.containsKey(spanData.getContext().getTraceId())) {
        Trace trace = convertTraceSpanDataToTrace(spanData);
        traces.put(spanData.getContext().getTraceId(), trace);
        trace.setSpans(new ArrayList<TraceSpan>());
      }
      traces.get(spanData.getContext().getTraceId()).getSpans().add(span);
    }
    
    // Only write out the ones where at least one trace span said to write.
    traces = Maps.filterKeys(traces, Predicates.in(shouldWriteTraces));
    
    // Write to the API.
    if (!traces.isEmpty()) {
      writeTraces(new Traces().setTraces(new ArrayList<Trace>(traces.values())));
    }
  }

  @Override
  public void writeSpans(TraceSpanData... spans) throws CloudTraceException {
    writeSpans(Arrays.asList(spans));
  }

  @Override
  public void shutdown() {}

  /**
   * Writes the trace list JSON to the Cloud Trace API.
   */
  private void writeTraces(Traces traces) throws CloudTraceException {
    GenericUrl url = buildUrl();
    try {
      String requestBody = objectMapper.writeValueAsString(traces);
      logger.info("Writing trace: " + requestBody);
      CloudTraceResponse response = requestFactory.executePatch(url, requestBody);
      if (response.getStatusCode() != HttpStatusCodes.STATUS_CODE_OK) {
        throw new CloudTraceException("Failed to write span, status = " + response.getStatusCode());
      }
    } catch (IOException e) {
      throw new CloudTraceException("Exception writing span to API, url=" + url, e);
    }
  }

  /**
   * Helper method that converts from the SDK span data model to the API trace model.
   */
  private Trace convertTraceSpanDataToTrace(TraceSpanData spanData) {
    Trace trace = new Trace();
    trace.setProjectId(projectId);
    trace.setTraceId(spanData.getContext().getTraceId());
    
    TraceSpan span = convertTraceSpanDataToSpan(spanData);
    trace.setSpans(ImmutableList.<TraceSpan>of(span));
    return trace;
  }

  /**
   * Helper method that pulls SDK span data into an API span.
   */
  private TraceSpan convertTraceSpanDataToSpan(TraceSpanData spanData) {
    TraceSpan span = new TraceSpan();
    span.setName(spanData.getName());
    span.setParentSpanId(spanData.getParentSpanId());
    span.setSpanId(spanData.getContext().getSpanId());
    span.setStartTime(ISODateTimeFormat.dateTime().withZoneUTC().print(
        new DateTime(spanData.getStartTimeMillis())));
    span.setEndTime(ISODateTimeFormat.dateTime().withZoneUTC().print(
        new DateTime(spanData.getEndTimeMillis())));
    
    Map<String, String> labels = new HashMap<>();
    for (Map.Entry<String, TraceSpanLabel> labelVal : spanData.getLabelMap().entrySet()) {
      labels.put(labelVal.getKey(), labelVal.getValue().getValue());
    }
    span.setLabels(labels);
    return span;
  }

  /**
   * Creates the URL to use to patch the trace with the given span.
   */
  private GenericUrl buildUrl() {
    String url = apiEndpoint + "v1/projects/" + projectId + "/traces/";
    return new GenericUrl(url);
  }

  /**
   * Validates the state before attempting to write a trace to the API.
   */
  private void checkState() {
    if (projectId == null || projectId.isEmpty()) {
      throw new IllegalStateException("Project id must be set");
    }
    if (apiEndpoint == null || apiEndpoint.isEmpty()) {
      throw new IllegalStateException("API endpoint must be set");
    }
  }
}
