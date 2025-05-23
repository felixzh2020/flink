/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.functions.sink;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.SupportsConcurrentExecutionAttempts;
import org.apache.flink.api.common.functions.util.PrintSinkOutputWriter;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;

import java.io.IOException;

/**
 * Sink implementation writing every element to the standard output or standard error stream.
 *
 * <p>Four possible format options:
 *
 * <ul>
 *   <li>{@code sinkIdentifier}:subtaskIndex> output <- {@code sinkIdentifier} provided and
 *       parallelism > 1,
 *   <li>{@code sinkIdentifier}> output <- {@code sinkIdentifier} provided and parallelism == 1
 *   <li>subtaskIndex> output <- no {@code sinkIdentifier} provided and parallelism > 1
 *   <li>output <- no {@code sinkIdentifier} provided and parallelism == 1
 * </ul>
 *
 * @param <IN> Input record type
 */
@PublicEvolving
public class PrintSink<IN> implements Sink<IN>, SupportsConcurrentExecutionAttempts {

    private static final long serialVersionUID = 1L;
    private final String sinkIdentifier;
    private final boolean isStdErr;

    /** Instantiates a print sink function that prints to STDOUT. */
    public PrintSink() {
        this("");
    }

    /**
     * Instantiates a print sink that prints to STDOUT or STDERR.
     *
     * @param isStdErr True, if the format should print to standard error instead of standard out.
     */
    public PrintSink(final boolean isStdErr) {
        this("", isStdErr);
    }

    /**
     * Instantiates a print sink that prints to STDOUT and gives a sink identifier.
     *
     * @param sinkIdentifier Message that identifies the sink and is prefixed to the output of the
     *     value
     */
    public PrintSink(final String sinkIdentifier) {
        this(sinkIdentifier, false);
    }

    /**
     * Instantiates a print sink that prints to STDOUT or STDERR and gives a sink identifier.
     *
     * @param sinkIdentifier Message that identifies the sink and is prefixed to the output of the
     *     value
     * @param isStdErr True if the sink should print to STDERR instead of STDOUT.
     */
    public PrintSink(final String sinkIdentifier, final boolean isStdErr) {
        this.sinkIdentifier = sinkIdentifier;
        this.isStdErr = isStdErr;
    }

    @Override
    public SinkWriter<IN> createWriter(WriterInitContext context) throws IOException {
        final PrintSinkOutputWriter<IN> writer =
                new PrintSinkOutputWriter<>(sinkIdentifier, isStdErr);
        writer.open(
                context.getTaskInfo().getIndexOfThisSubtask(),
                context.getTaskInfo().getNumberOfParallelSubtasks());
        return writer;
    }

    @Override
    public String toString() {
        return "Print to " + (isStdErr ? "System.err" : "System.out");
    }
}
