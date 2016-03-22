package com.google.cloud.trace.sdk.gae;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Inject;

import com.google.appengine.api.taskqueue.DeferredTask;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.cloud.trace.sdk.CloudTraceException;
import com.google.cloud.trace.sdk.TraceSpanData;
import com.google.cloud.trace.sdk.TraceWriter;
import com.google.common.collect.Lists;

public class AsyncCloudTraceWriter implements TraceWriter {

    protected static final Logger LOGGER = Logger.getLogger(AsyncCloudTraceWriter.class.getName());
    
    private Queue queue;
    
    @Inject
    public AsyncCloudTraceWriter(Queue queue) {
        super();
        this.queue = queue;
    }

    @Override
    public void writeSpan(TraceSpanData span) throws CloudTraceException {
        CloudTracingDeferredTask task = new CloudTracingDeferredTask(span);
        enqueue(task);
    }

    @Override
    public void writeSpans(List<TraceSpanData> spans) throws CloudTraceException {
        CloudTracingDeferredTask task = new CloudTracingDeferredTask(spans);
        enqueue(task);
    }

    @Override
    public void writeSpans(TraceSpanData... spans) throws CloudTraceException {
        CloudTracingDeferredTask task = new CloudTracingDeferredTask(Arrays.asList(spans));
        enqueue(task);
    }

    private void enqueue(CloudTracingDeferredTask task) {
        LOGGER.fine("Adding task for async cloud tracing: ");
        queue.add(null, TaskOptions.Builder.withPayload(task));
    }
    
    @Override
    public void shutdown() throws CloudTraceException {
    }

    
    public static class CloudTracingDeferredTask implements DeferredTask {
        
        private static final long serialVersionUID = 1L;
        
        private List<TraceSpanData> spans;
       
        public CloudTracingDeferredTask(TraceSpanData span) {
            spans = Lists.newArrayList(span);
        }
        
        public CloudTracingDeferredTask(List<TraceSpanData> spans) {
            super();
            this.spans = spans;
        }

        @Inject
        static TraceWriter writer;

        @Override
        public void run() {
            if(spans != null && !spans.isEmpty()) {
                try {
                    writer.writeSpans(spans);
                } catch (CloudTraceException e) {
                }
            }
        }
        
        public static void setTraceWriter(TraceWriter writer) {
            CloudTracingDeferredTask.writer = writer;
        }
        
    }
    
}
