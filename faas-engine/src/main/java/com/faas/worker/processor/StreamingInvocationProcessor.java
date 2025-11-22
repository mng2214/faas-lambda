package com.faas.worker.processor;

import com.faas.function.LocalLambdaFunction;
import com.faas.dto.EventRequest;
import com.faas.storage.WorkerStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * STREAM / STREAMING mode.
 */
@Slf4j
public class StreamingInvocationProcessor {

    private static final String KEY_STREAM_ID = "_streamId";
    private static final String KEY_CALLBACK_URL = "_callbackUrl";

    private final WorkerStorage storage;
    private final ObjectMapper objectMapper;
    private final Runnable onInvocationFinished;
    private final SimpleInvocationProcessor simpleProcessor;

    public StreamingInvocationProcessor(WorkerStorage storage,
                                        ObjectMapper objectMapper,
                                        Runnable onInvocationFinished,
                                        SimpleInvocationProcessor simpleProcessor) {
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.onInvocationFinished = onInvocationFinished;
        this.simpleProcessor = simpleProcessor;
    }

    public void processStream(EventRequest event,
                              LocalLambdaFunction function,
                              Map<String, Object> payload) {
        String functionName = event.getFunctionName();
        String streamId = (String) payload.get(KEY_STREAM_ID);

        if (!(function instanceof LocalLambdaFunction.Streaming streamingFn)) {
            log.warn("Function '{}' is not LocalLambdaFunction.Streaming, falling back to SIMPLE", functionName);
            simpleProcessor.processSimple(event, function, payload, false);
            return;
        }

        try {
            LocalLambdaFunction.Streaming.StreamEmitter emitter =
                    new LocalLambdaFunction.Streaming.StreamEmitter() {
                        @Override
                        public void next(Object chunk) {
                            try {
                                Map<String, Object> record = new HashMap<>();
                                record.put("eventId", event.getEventId());
                                record.put("functionName", functionName);
                                record.put("chunk", chunk);
                                record.put("timestamp", Instant.now().toString());
                                if (streamId != null) {
                                    record.put("streamId", streamId);
                                }
                                Object callbackUrl = payload.get(KEY_CALLBACK_URL);
                                if (callbackUrl != null) {
                                    record.put("callbackUrl", callbackUrl);
                                }
                                storage.appendStreamChunk(
                                        streamId != null ? streamId : event.getEventId(),
                                        toJson(record)
                                );
                            } catch (Exception e) {
                                log.error("Failed to store stream chunk for '{}'", functionName, e);
                            }
                        }

                        @Override
                        public void complete() {
                            try {
                                storage.completeStream(
                                        streamId != null ? streamId : event.getEventId()
                                );
                            } catch (Exception e) {
                                log.error("Failed to mark stream complete for '{}'", functionName, e);
                            }
                        }

                        @Override
                        public void error(Throwable t) {
                            try {
                                Map<String, Object> record = new HashMap<>();
                                record.put("eventId", event.getEventId());
                                record.put("functionName", functionName);
                                record.put("errorMessage", t.getMessage());
                                record.put("timestamp", Instant.now().toString());
                                if (streamId != null) {
                                    record.put("streamId", streamId);
                                }
                                storage.failStream(
                                        streamId != null ? streamId : event.getEventId(),
                                        toJson(record)
                                );
                            } catch (Exception e) {
                                log.error("Failed to mark stream error for '{}'", functionName, e);
                            } finally {
                                storage.incrementError();
                            }
                        }
                    };

            streamingFn.handleStream(payload, emitter);
        } catch (Exception ex) {
            log.error("Streaming function '{}' failed", functionName, ex);
            storage.incrementError();
            tryStoreFunctionError(event, functionName, ex);
        } finally {
            onInvocationFinished.run();
        }
    }

    private String toJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private void tryStoreFunctionError(EventRequest event, String functionName, Exception ex) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("eventId", event.getEventId());
            error.put("functionName", functionName);
            error.put("errorMessage", ex.getMessage());
            error.put("timestamp", Instant.now().toString());
            storage.storeFunctionError(functionName, toJson(error));
        } catch (Exception e) {
            log.error("Failed to store function error for '{}'", functionName, e);
        }
    }
}
