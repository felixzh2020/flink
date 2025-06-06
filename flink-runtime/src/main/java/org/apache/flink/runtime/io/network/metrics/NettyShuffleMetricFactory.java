/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.metrics;

import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.View;
import org.apache.flink.runtime.io.network.buffer.NetworkBufferPool;
import org.apache.flink.runtime.io.network.partition.ResultPartition;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.metrics.MetricNames;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** Factory for netty shuffle service metrics. */
public class NettyShuffleMetricFactory {

    // shuffle environment level metrics: Shuffle.Netty.*

    private static final String METRIC_TOTAL_MEMORY_SEGMENT = "TotalMemorySegments";
    private static final String METRIC_TOTAL_MEMORY = "TotalMemory";

    private static final String METRIC_AVAILABLE_MEMORY_SEGMENT = "AvailableMemorySegments";
    private static final String METRIC_AVAILABLE_MEMORY = "AvailableMemory";

    private static final String METRIC_USED_MEMORY_SEGMENT = "UsedMemorySegments";
    private static final String METRIC_USED_MEMORY = "UsedMemory";

    private static final String METRIC_REQUESTED_MEMORY_USAGE = "RequestedMemoryUsage";

    // task level metric group structure: Shuffle.Netty.<Input|Output>.Buffers

    private static final String METRIC_GROUP_SHUFFLE = "Shuffle";
    private static final String METRIC_GROUP_NETTY = "Netty";
    public static final String METRIC_GROUP_OUTPUT = "Output";
    public static final String METRIC_GROUP_INPUT = "Input";
    private static final String METRIC_GROUP_BUFFERS = "Buffers";

    // task level output metrics: Shuffle.Netty.Output.*

    private static final String METRIC_OUTPUT_QUEUE_LENGTH = "outputQueueLength";
    private static final String METRIC_OUTPUT_QUEUE_SIZE = "outputQueueSize";
    private static final String METRIC_OUTPUT_POOL_USAGE = "outPoolUsage";

    // task level input metrics: Shuffle.Netty.Input.*

    private static final String METRIC_INPUT_QUEUE_LENGTH = "inputQueueLength";
    private static final String METRIC_INPUT_QUEUE_SIZE = "inputQueueSize";
    private static final String METRIC_INPUT_POOL_USAGE = "inPoolUsage";
    private static final String METRIC_INPUT_FLOATING_BUFFERS_USAGE = "inputFloatingBuffersUsage";
    private static final String METRIC_INPUT_EXCLUSIVE_BUFFERS_USAGE = "inputExclusiveBuffersUsage";

    private NettyShuffleMetricFactory() {}

    public static void registerShuffleMetrics(
            MetricGroup metricGroup, NetworkBufferPool networkBufferPool) {
        checkNotNull(metricGroup);
        checkNotNull(networkBufferPool);

        internalRegisterShuffleMetrics(metricGroup, networkBufferPool);
    }

    private static void internalRegisterShuffleMetrics(
            MetricGroup parentMetricGroup, NetworkBufferPool networkBufferPool) {
        MetricGroup shuffleGroup = parentMetricGroup.addGroup(METRIC_GROUP_SHUFFLE);
        MetricGroup networkGroup = shuffleGroup.addGroup(METRIC_GROUP_NETTY);

        networkGroup.gauge(
                METRIC_TOTAL_MEMORY_SEGMENT, networkBufferPool::getTotalNumberOfMemorySegments);
        networkGroup.gauge(METRIC_TOTAL_MEMORY, networkBufferPool::getTotalMemory);

        networkGroup.gauge(
                METRIC_AVAILABLE_MEMORY_SEGMENT,
                networkBufferPool::getNumberOfAvailableMemorySegments);
        networkGroup.gauge(METRIC_AVAILABLE_MEMORY, networkBufferPool::getAvailableMemory);

        networkGroup.gauge(
                METRIC_USED_MEMORY_SEGMENT, networkBufferPool::getNumberOfUsedMemorySegments);
        networkGroup.gauge(METRIC_USED_MEMORY, networkBufferPool::getUsedMemory);

        networkGroup.gauge(
                METRIC_REQUESTED_MEMORY_USAGE, new RequestedMemoryUsageMetric(networkBufferPool));
    }

    public static MetricGroup createShuffleIOOwnerMetricGroup(MetricGroup parentGroup) {
        return parentGroup.addGroup(METRIC_GROUP_SHUFFLE).addGroup(METRIC_GROUP_NETTY);
    }

    public static void registerOutputMetrics(
            boolean isDetailedMetrics,
            MetricGroup outputGroup,
            ResultPartition[] resultPartitions) {
        registerOutputMetrics(
                isDetailedMetrics,
                outputGroup,
                outputGroup.addGroup(METRIC_GROUP_BUFFERS),
                resultPartitions);
    }

    private static void registerOutputMetrics(
            boolean isDetailedMetrics,
            MetricGroup outputGroup,
            MetricGroup buffersGroup,
            ResultPartition[] resultPartitions) {
        if (isDetailedMetrics) {
            ResultPartitionMetrics.registerQueueLengthMetrics(outputGroup, resultPartitions);
        }
        buffersGroup.gauge(METRIC_OUTPUT_QUEUE_LENGTH, new OutputBuffersGauge(resultPartitions));
        buffersGroup.gauge(METRIC_OUTPUT_QUEUE_SIZE, new OutputBuffersSizeGauge(resultPartitions));
        buffersGroup.gauge(
                METRIC_OUTPUT_POOL_USAGE, new OutputBufferPoolUsageGauge(resultPartitions));
    }

    public static void registerInputMetrics(
            boolean isDetailedMetrics, MetricGroup inputGroup, SingleInputGate[] inputGates) {
        registerInputMetrics(
                isDetailedMetrics,
                inputGroup,
                inputGroup.addGroup(METRIC_GROUP_BUFFERS),
                inputGates);
    }

    private static void registerInputMetrics(
            boolean isDetailedMetrics,
            MetricGroup inputGroup,
            MetricGroup buffersGroup,
            SingleInputGate[] inputGates) {
        if (isDetailedMetrics) {
            InputGateMetrics.registerQueueLengthMetrics(inputGroup, inputGates);
        }

        buffersGroup.gauge(METRIC_INPUT_QUEUE_LENGTH, new InputBuffersGauge(inputGates));
        buffersGroup.gauge(METRIC_INPUT_QUEUE_SIZE, new InputBuffersSizeGauge(inputGates));

        FloatingBuffersUsageGauge floatingBuffersUsageGauge =
                new FloatingBuffersUsageGauge(inputGates);
        ExclusiveBuffersUsageGauge exclusiveBuffersUsageGauge =
                new ExclusiveBuffersUsageGauge(inputGates);
        CreditBasedInputBuffersUsageGauge creditBasedInputBuffersUsageGauge =
                new CreditBasedInputBuffersUsageGauge(
                        floatingBuffersUsageGauge, exclusiveBuffersUsageGauge, inputGates);
        buffersGroup.gauge(METRIC_INPUT_EXCLUSIVE_BUFFERS_USAGE, exclusiveBuffersUsageGauge);
        buffersGroup.gauge(METRIC_INPUT_FLOATING_BUFFERS_USAGE, floatingBuffersUsageGauge);
        buffersGroup.gauge(METRIC_INPUT_POOL_USAGE, creditBasedInputBuffersUsageGauge);
    }

    public static void registerDebloatingTaskMetrics(
            SingleInputGate[] inputGates, MetricGroup taskGroup) {
        taskGroup.gauge(
                MetricNames.ESTIMATED_TIME_TO_CONSUME_BUFFERS, new TimeToConsumeGauge(inputGates));
    }

    /**
     * This is a small hack. Instead of spawning a custom thread to monitor {@link
     * NetworkBufferPool} usage, we are re-using {@link View#update()} method for this purpose.
     */
    private static class RequestedMemoryUsageMetric implements Gauge<Integer>, View {
        private final NetworkBufferPool networkBufferPool;

        public RequestedMemoryUsageMetric(NetworkBufferPool networkBufferPool) {
            this.networkBufferPool = networkBufferPool;
        }

        @Override
        public Integer getValue() {
            return networkBufferPool.getEstimatedRequestedSegmentsUsage();
        }

        @Override
        public void update() {
            networkBufferPool.maybeLogUsageWarning();
        }
    }
}
