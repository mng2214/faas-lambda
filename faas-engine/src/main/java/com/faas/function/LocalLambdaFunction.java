//package com.faas.function;
//
//import com.faas.enums.WorkloadType;
//
//import java.util.Map;
//
///**
// * Base functional interface for local FaaS functions.
// * <p>
// * Backward-compatible: existing functions that implement only
// * {@link LocalLambdaFunction} continue to work in SIMPLE mode.
// */
//public interface LocalLambdaFunction {
//
//    /**
//     * Hint to the engine which executor to use.
//     * IO_BOUND by default.
//     */
//    default WorkloadType workloadType() {
//        return WorkloadType.IO_BOUND;
//    }
//
//    /**
//     * Maximum retry attempts for this function.
//     * Engine will take the minimum between function-level and global config.
//     */
//    default int maxRetries() {
//        return 3;
//    }
//
//    /**
//     * Human-readable description for catalog / UI.
//     */
//    default String description() {
//        return "No description provided.";
//    }
//
//    /**
//     * Display name for catalog / UI. By default equals to {@link #getName()}.
//     */
//    default String displayName() {
//        return getName();
//    }
//
//    /**
//     * Unique function name (within registry).
//     */
//    String getName();
//
//    /**
//     * Core handler for SIMPLE / CHAIN modes.
//     */
//    Map<String, Object> handle(Map<String, Object> input);
//
//    // ----------------------------------------------------------------------
//    // Optional extension for streaming / pipe-style functions.
//    // ----------------------------------------------------------------------
//
//    /**
//     * Optional marker interface for streaming functions.
//     * <p>
//     * Engine will call {@link #handleStream(Map, StreamEmitter)} instead of
//     * {@link LocalLambdaFunction#handle(Map)} when event payload contains
//     * <code>_mode = "STREAM"</code> or <code>_mode = "STREAMING"</code>.
//     */
//    interface Streaming extends LocalLambdaFunction {
//
//        /**
//         * Streaming handler: function can emit multiple chunks via
//         * {@link StreamEmitter#next(Object)}, then either
//         * {@link StreamEmitter#complete()} or {@link StreamEmitter#error(Throwable)}.
//         */
//        void handleStream(Map<String, Object> input, StreamEmitter emitter);
//
//        interface StreamEmitter {
//            void next(Object chunk);
//            void complete();
//            void error(Throwable t);
//        }
//    }
//}
