/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.pravega;

import io.pravega.client.ClientFactory;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.*;
import io.pravega.client.stream.impl.ByteArraySerializer;
import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.FlowFileFilters;
import org.apache.nifi.stream.io.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Tags({"Pravega", "Nautilus", "Get", "Ingest", "Ingress", "Receive", "Consume", "Subscribe", "Stream"})
@CapabilityDescription("Consumes events from Pravega.")
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@SupportsBatching
@SeeAlso({PublishPravega.class})
public class ConsumePravega extends AbstractPravegaProcessor {

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles received from Pravega.")
            .build();

    ClientFactory cachedClientFactory;
    EventStreamReader<byte[]> cachedReader;
    String readerGroupName;
    ReaderGroup readerGroup;
    ScheduledExecutorService scheduler;

    private static final List<PropertyDescriptor> descriptors;
    private static final Set<Relationship> relationships;

    static {
        final List<PropertyDescriptor> innerDescriptorsList = getAbstractPropertyDescriptors();
        descriptors = Collections.unmodifiableList(innerDescriptorsList);

        final Set<Relationship> innerRelationshipsSet = new HashSet<>();
        innerRelationshipsSet.add(REL_SUCCESS);
        relationships = Collections.unmodifiableSet(innerRelationshipsSet);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        super.init(context);
        scheduler = Executors.newScheduledThreadPool(1);
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        synchronized (this) {
            // TODO: must coordinate reader group name across cluster.
            readerGroupName = UUID.randomUUID().toString().replace("-", "");
            logger.debug("readerGroupName={}", new Object[]{readerGroupName});
        }
    }

    @OnStopped
    public void onStop(final ProcessContext context) {
        synchronized (this) {
            logger.debug("ConsumePravega.onStop");
            if (cachedReader != null) {
                cachedReader.close();
                cachedReader = null;
            }
            if (cachedClientFactory != null) {
                cachedClientFactory.close();
                cachedClientFactory = null;
            }
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        logger.debug("ConsumePravega.onTrigger: BEGIN");

        final String controller = context.getProperty(PROP_CONTROLLER).getValue();
        final String scope = context.getProperty(PROP_SCOPE).getValue();
        final String streamName = context.getProperty(PROP_STREAM).getValue();
        final String transitUri = buildTransitURI(controller, scope, streamName);
        final long startTime = System.nanoTime();
        final EventStreamReader<byte[]> reader = getReader(context);

        final long READER_TIMEOUT_MS = 1000;
        long eventCount = 0;
        try {
            while (true) {
                final EventRead<byte[]> event = reader.readNextEvent(READER_TIMEOUT_MS);
//                if (event == null) {
//                    break;
//                }
                if (event.getEvent() == null) {
                    break;
                }

                logger.debug("size={}, event={}",
                        new Object[]{event.getEvent().length, event});

                FlowFile flowFile = session.create();
                Map<String, String> attrs = new HashMap<>();
                attrs.put("pravega.event", event.toString());
                attrs.put("pravega.event.getEventPointer", event.getEventPointer().toString());
                flowFile = session.putAllAttributes(flowFile, attrs);

                flowFile = session.write(flowFile, new OutputStreamCallback() {
                    @Override
                    public void process(final OutputStream out) throws IOException {
                        out.write(event.getEvent());
                    }
                });

                session.getProvenanceReporter().receive(flowFile, transitUri);
                session.transfer(flowFile, REL_SUCCESS);
                session.commit();
                eventCount++;
            }
        }
        catch (ReinitializationRequiredException e) {
            throw new ProcessException(e);
        }

        final long transmissionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        logger.info("Received {} events in {} milliseconds from Pravega stream {}.",
                new Object[]{eventCount, transmissionMillis, transitUri});

        if (false) {
            final String checkpointName = UUID.randomUUID().toString();
            logger.debug("Calling initiateCheckpoint");
            CompletableFuture<Checkpoint> checkpointFuture = readerGroup.initiateCheckpoint(checkpointName, scheduler);
            checkpointFuture.thenAccept((Checkpoint checkpoint) -> {
                logger.debug("checkpoint={}", new Object[]{checkpoint});
            });
        }
//        Map<Stream, StreamCut> streamCuts = readerGroup.getStreamCuts();
//        logger.info("streamCuts={}", new Object[]{streamCuts});

        logger.debug("ConsumePravega.onTrigger: END");
    }

    protected EventStreamReader<byte[]> getReader(ProcessContext context) {
        synchronized (this) {
            if (cachedReader == null) {
                URI controllerURI;
                try {
                    controllerURI = new URI(context.getProperty(PROP_CONTROLLER).getValue());
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                final String scope = context.getProperty(PROP_SCOPE).getValue();
                final String streamName = context.getProperty(PROP_STREAM).getValue();
                final StreamConfiguration streamConfig = StreamConfiguration.builder()
                        .scalingPolicy(ScalingPolicy.fixed(1))
                        .build();
                // TODO: get latest stream cut
                final ReaderGroupConfig readerGroupConfig = ReaderGroupConfig.builder()
                        .stream(Stream.of(scope, streamName), StreamCut.UNBOUNDED)
                        .disableAutomaticCheckpoints()
                        .build();
                final String readerId = getIdentifier();

                logger.info("Using reader group {}, reader ID {} to read from Pravega stream {}/{}.",
                        new Object[]{readerGroupName, readerId, scope, streamName});

                // TODO: Create scope and stream based on additional properties.
                try (final StreamManager streamManager = StreamManager.create(controllerURI)) {
                    streamManager.createScope(scope);
                    streamManager.createStream(scope, streamName, streamConfig);
                }

                try (ReaderGroupManager readerGroupManager = ReaderGroupManager.withScope(scope, controllerURI)) {
                    readerGroupManager.createReaderGroup(readerGroupName, readerGroupConfig);
                    readerGroup = readerGroupManager.getReaderGroup(readerGroupName);
                }

                final ClientFactory clientFactory = ClientFactory.withScope(scope, controllerURI);
                final EventStreamReader<byte[]> reader = clientFactory.createReader(
                        readerId,
                        readerGroupName,
                        new ByteArraySerializer(),
                        ReaderConfig.builder().build());

                cachedClientFactory = clientFactory;
                cachedReader = reader;
            }
            return cachedReader;
        }
    }
}
