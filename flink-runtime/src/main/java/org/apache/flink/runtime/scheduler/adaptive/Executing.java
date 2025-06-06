/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.scheduler.adaptive;

import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.checkpoint.CheckpointScheduling;
import org.apache.flink.runtime.checkpoint.CheckpointStatsListener;
import org.apache.flink.runtime.checkpoint.CompletedCheckpoint;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.AccessExecutionGraph;
import org.apache.flink.runtime.executiongraph.AccessExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.ArchivedExecutionGraph;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.scheduler.ExecutionGraphHandler;
import org.apache.flink.runtime.scheduler.OperatorCoordinatorHandler;
import org.apache.flink.runtime.scheduler.adaptive.allocator.VertexParallelism;
import org.apache.flink.runtime.scheduler.exceptionhistory.ExceptionHistoryEntry;
import org.apache.flink.runtime.scheduler.stopwithsavepoint.StopWithSavepointTerminationManager;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/** State which represents a running job with an {@link ExecutionGraph} and assigned slots. */
class Executing extends StateWithExecutionGraph
        implements ResourceListener, StateTransitionManager.Context, CheckpointStatsListener {

    private final Context context;

    private final StateTransitionManager stateTransitionManager;
    private final int rescaleOnFailedCheckpointCount;
    // null indicates that there was no change event observed, yet
    @Nullable private AtomicInteger failedCheckpointCountdown;

    Executing(
            ExecutionGraph executionGraph,
            ExecutionGraphHandler executionGraphHandler,
            OperatorCoordinatorHandler operatorCoordinatorHandler,
            Logger logger,
            Context context,
            ClassLoader userCodeClassLoader,
            List<ExceptionHistoryEntry> failureCollection,
            Function<StateTransitionManager.Context, StateTransitionManager>
                    stateTransitionManagerFactory,
            int rescaleOnFailedCheckpointCount) {
        super(
                context,
                executionGraph,
                executionGraphHandler,
                operatorCoordinatorHandler,
                logger,
                userCodeClassLoader,
                failureCollection);
        this.context = context;
        Preconditions.checkState(
                executionGraph.getState() == JobStatus.RUNNING, "Assuming running execution graph");

        this.stateTransitionManager = stateTransitionManagerFactory.apply(this);

        Preconditions.checkArgument(
                rescaleOnFailedCheckpointCount > 0,
                "The rescaleOnFailedCheckpointCount should be larger than 0.");
        this.rescaleOnFailedCheckpointCount = rescaleOnFailedCheckpointCount;
        this.failedCheckpointCountdown = null;

        deploy();

        // check if new resources have come available in the meantime
        context.runIfState(
                this,
                () -> {
                    stateTransitionManager.onChange();
                    stateTransitionManager.onTrigger();
                },
                Duration.ZERO);
    }

    @Override
    public boolean hasSufficientResources() {
        return parallelismChanged() && context.hasSufficientResources();
    }

    @Override
    public boolean hasDesiredResources() {
        return parallelismChanged() && context.hasDesiredResources();
    }

    private boolean parallelismChanged() {
        final VertexParallelism currentParallelism =
                extractCurrentVertexParallelism(getExecutionGraph());
        return context.getAvailableVertexParallelism()
                .map(
                        availableParallelism ->
                                availableParallelism.getVertices().stream()
                                        .anyMatch(
                                                vertex ->
                                                        currentParallelism.getParallelism(vertex)
                                                                != availableParallelism
                                                                        .getParallelism(vertex)))
                .orElse(false);
    }

    private static VertexParallelism extractCurrentVertexParallelism(
            AccessExecutionGraph executionGraph) {
        return new VertexParallelism(
                executionGraph.getAllVertices().values().stream()
                        .collect(
                                Collectors.toMap(
                                        AccessExecutionJobVertex::getJobVertexId,
                                        AccessExecutionJobVertex::getParallelism)));
    }

    @Override
    public ScheduledFuture<?> scheduleOperation(Runnable callback, Duration delay) {
        return context.runIfState(this, callback, delay);
    }

    @Override
    public void transitionToSubsequentState() {
        context.goToRestarting(
                getExecutionGraph(),
                getExecutionGraphHandler(),
                getOperatorCoordinatorHandler(),
                Duration.ofMillis(0L),
                context.getAvailableVertexParallelism()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Resources must be available when rescaling.")),
                getFailures());
    }

    @Override
    public JobStatus getJobStatus() {
        return JobStatus.RUNNING;
    }

    @Override
    public void cancel() {
        context.goToCanceling(
                getExecutionGraph(),
                getExecutionGraphHandler(),
                getOperatorCoordinatorHandler(),
                getFailures());
    }

    @Override
    void onFailure(Throwable cause, CompletableFuture<Map<String, String>> failureLabels) {
        FailureResultUtil.restartOrFail(
                context.howToHandleFailure(cause, failureLabels), context, this);
    }

    @Override
    void onGloballyTerminalState(JobStatus globallyTerminalState) {
        context.goToFinished(ArchivedExecutionGraph.createFrom(getExecutionGraph()));
    }

    @Override
    public void onLeave(Class<? extends State> newState) {
        stateTransitionManager.close();
        super.onLeave(newState);
    }

    private void deploy() {
        for (ExecutionJobVertex executionJobVertex :
                getExecutionGraph().getVerticesTopologically()) {
            for (ExecutionVertex executionVertex : executionJobVertex.getTaskVertices()) {
                if (executionVertex.getExecutionState() == ExecutionState.CREATED
                        || executionVertex.getExecutionState() == ExecutionState.SCHEDULED) {
                    deploySafely(executionVertex);
                }
            }
        }
    }

    private void deploySafely(ExecutionVertex executionVertex) {
        try {
            executionVertex.deploy();
        } catch (JobException e) {
            handleDeploymentFailure(executionVertex, e);
        }
    }

    private void handleDeploymentFailure(ExecutionVertex executionVertex, JobException e) {
        executionVertex.markFailed(e);
    }

    @Override
    public void onNewResourcesAvailable() {
        stateTransitionManager.onChange();
        initializeFailedCheckpointCountdownIfUnset();
    }

    @Override
    public void onNewResourceRequirements() {
        stateTransitionManager.onChange();
        initializeFailedCheckpointCountdownIfUnset();
    }

    @Override
    public void onCompletedCheckpoint() {
        triggerPotentialRescale();
    }

    @Override
    public void onFailedCheckpoint() {
        if (this.failedCheckpointCountdown != null
                && this.failedCheckpointCountdown.decrementAndGet() <= 0) {
            triggerPotentialRescale();
        }
    }

    private void triggerPotentialRescale() {
        stateTransitionManager.onTrigger();
        this.failedCheckpointCountdown = null;
    }

    private void initializeFailedCheckpointCountdownIfUnset() {
        if (failedCheckpointCountdown == null) {
            this.failedCheckpointCountdown = new AtomicInteger(this.rescaleOnFailedCheckpointCount);
        }
    }

    CompletableFuture<String> stopWithSavepoint(
            @Nullable final String targetDirectory,
            boolean terminate,
            SavepointFormatType formatType) {
        final ExecutionGraph executionGraph = getExecutionGraph();

        StopWithSavepointTerminationManager.checkSavepointActionPreconditions(
                executionGraph.getCheckpointCoordinator(),
                targetDirectory,
                executionGraph.getJobID(),
                getLogger());

        getLogger().info("Triggering stop-with-savepoint for job {}.", executionGraph.getJobID());

        CheckpointScheduling schedulingProvider = new CheckpointSchedulingProvider(executionGraph);

        schedulingProvider.stopCheckpointScheduler();

        final CompletableFuture<String> savepointFuture =
                Objects.requireNonNull(executionGraph.getCheckpointCoordinator())
                        .triggerSynchronousSavepoint(terminate, targetDirectory, formatType)
                        .thenApply(CompletedCheckpoint::getExternalPointer);
        return context.goToStopWithSavepoint(
                executionGraph,
                getExecutionGraphHandler(),
                getOperatorCoordinatorHandler(),
                schedulingProvider,
                savepointFuture,
                getFailures());
    }

    /** Context of the {@link Executing} state. */
    interface Context
            extends StateWithExecutionGraph.Context,
                    StateTransitions.ToCancelling,
                    StateTransitions.ToFailing,
                    StateTransitions.ToRestarting,
                    StateTransitions.ToStopWithSavepoint {

        /**
         * Asks how to handle the failure.
         *
         * @param failure failure describing the failure cause
         * @param failureLabels future of labels from error classification.
         * @return {@link FailureResult} which describes how to handle the failure
         */
        FailureResult howToHandleFailure(
                Throwable failure, CompletableFuture<Map<String, String>> failureLabels);

        /**
         * Returns the {@link VertexParallelism} that can be provided by the currently available
         * slots.
         */
        Optional<VertexParallelism> getAvailableVertexParallelism();

        /**
         * Runs the given action after a delay if the state at this time equals the expected state.
         *
         * @param expectedState expectedState describes the required state at the time of running
         *     the action
         * @param action action to run if the expected state equals the actual state
         * @param delay delay after which to run the action
         * @return a ScheduledFuture representing pending completion of the task
         */
        ScheduledFuture<?> runIfState(State expectedState, Runnable action, Duration delay);

        /**
         * Checks whether we have the desired resources.
         *
         * @return {@code true} if we have enough resources; otherwise {@code false}
         */
        boolean hasDesiredResources();

        /**
         * Checks if we currently have sufficient resources for executing the job.
         *
         * @return {@code true} if we have sufficient resources; otherwise {@code false}
         */
        boolean hasSufficientResources();
    }

    static class Factory implements StateFactory<Executing> {

        private final Context context;
        private final Logger log;
        private final ExecutionGraph executionGraph;
        private final ExecutionGraphHandler executionGraphHandler;
        private final OperatorCoordinatorHandler operatorCoordinatorHandler;
        private final ClassLoader userCodeClassLoader;
        private final List<ExceptionHistoryEntry> failureCollection;
        private final Function<StateTransitionManager.Context, StateTransitionManager>
                stateTransitionManagerFactory;
        private final int rescaleOnFailedCheckpointCount;

        Factory(
                ExecutionGraph executionGraph,
                ExecutionGraphHandler executionGraphHandler,
                OperatorCoordinatorHandler operatorCoordinatorHandler,
                Logger log,
                Context context,
                ClassLoader userCodeClassLoader,
                List<ExceptionHistoryEntry> failureCollection,
                Function<StateTransitionManager.Context, StateTransitionManager>
                        stateTransitionManagerFactory,
                int rescaleOnFailedCheckpointCount) {
            this.context = context;
            this.log = log;
            this.executionGraph = executionGraph;
            this.executionGraphHandler = executionGraphHandler;
            this.operatorCoordinatorHandler = operatorCoordinatorHandler;
            this.userCodeClassLoader = userCodeClassLoader;
            this.failureCollection = failureCollection;
            this.stateTransitionManagerFactory = stateTransitionManagerFactory;
            this.rescaleOnFailedCheckpointCount = rescaleOnFailedCheckpointCount;
        }

        public Class<Executing> getStateClass() {
            return Executing.class;
        }

        public Executing getState() {
            return new Executing(
                    executionGraph,
                    executionGraphHandler,
                    operatorCoordinatorHandler,
                    log,
                    context,
                    userCodeClassLoader,
                    failureCollection,
                    stateTransitionManagerFactory,
                    rescaleOnFailedCheckpointCount);
        }
    }
}
