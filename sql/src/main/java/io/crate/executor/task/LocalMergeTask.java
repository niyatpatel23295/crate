/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.task;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.breaker.RamAccountingContext;
import io.crate.core.collections.Bucket;
import io.crate.core.collections.BucketPage;
import io.crate.executor.JobTask;
import io.crate.executor.TaskResult;
import io.crate.executor.callbacks.OperationFinishedStatsTablesCallback;
import io.crate.jobs.JobContextService;
import io.crate.operation.*;
import io.crate.operation.collect.StatsTables;
import io.crate.planner.node.dql.MergeNode;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.threadpool.ThreadPool;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * merging rows locally on the handler
 *
 */
public class LocalMergeTask extends JobTask {

    private static final ESLogger LOGGER = Loggers.getLogger(LocalMergeTask.class);

    private final PageDownstreamFactory pageDownstreamFactory;
    private final MergeNode mergeNode;
    private final StatsTables statsTables;
    private final SettableFuture<TaskResult> result;
    private final List<ListenableFuture<TaskResult>> resultList;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPool threadPool;

    private List<ListenableFuture<TaskResult>> upstreamResults;
    private JobContextService jobContextService;

    public LocalMergeTask(UUID jobId,
                          PageDownstreamFactory pageDownstreamFactory,
                          JobContextService jobContextService,
                          MergeNode mergeNode,
                          StatsTables statsTables,
                          CircuitBreaker circuitBreaker,
                          ThreadPool threadPool) {
        super(jobId);
        this.pageDownstreamFactory = pageDownstreamFactory;
        this.jobContextService = jobContextService;
        this.mergeNode = mergeNode;
        this.statsTables = statsTables;
        this.circuitBreaker = circuitBreaker;
        this.threadPool = threadPool;
        this.result = SettableFuture.create();
        this.resultList = Collections.<ListenableFuture<TaskResult>>singletonList(this.result);
    }

    /**
     * only operate if we have an upstream as data always comes in via upstream results
     *
     * start listener for every upStream Result
     * if last listener is done he sets the local result.
     */
    @Override
    public void start() {
        if (upstreamResults == null) {
            result.set(TaskResult.EMPTY_RESULT);
            return;
        }

        final UUID operationId = UUID.randomUUID();
        final RamAccountingContext ramAccountingContext = RamAccountingContext.forExecutionNode(circuitBreaker, mergeNode, operationId);

        RowDownstream rowDownstream = new QueryResultRowDownstream(result, jobId(), mergeNode.executionNodeId(), jobContextService);
        final PageDownstream pageDownstream = pageDownstreamFactory.createMergeNodePageDownstream(
                mergeNode,
                rowDownstream,
                ramAccountingContext,
                Optional.of(threadPool.executor(ThreadPool.Names.GENERIC))
        );

        statsTables.operationStarted(operationId, mergeNode.jobId(), mergeNode.name());
        Futures.addCallback(result, new OperationFinishedStatsTablesCallback<>(operationId, statsTables, ramAccountingContext));

        BucketPage page = taskResultToBucketPage();
        pageDownstream.nextPage(page, new PageConsumeListener() {
            @Override
            public void needMore() {
                // currently only one page
                LOGGER.trace("{} need more", mergeNode.jobId());
                this.finish();
            }

            @Override
            public void finish() {
                LOGGER.trace("{} page finished", mergeNode.jobId());
                pageDownstream.finish();
            }
        });
    }

    private BucketPage taskResultToBucketPage() {
        return new BucketPage(Lists.transform(upstreamResults, new Function<ListenableFuture<TaskResult>, ListenableFuture<Bucket>>() {

            @Nullable
            @Override
            public ListenableFuture<Bucket> apply(@Nullable ListenableFuture<TaskResult> future) {
                assert future != null : "taskresult future is null";
                return Futures.transform(future, new Function<TaskResult, Bucket>() {

                    @Nullable
                    @Override
                    public Bucket apply(TaskResult taskResult) {
                        traceLogResult(taskResult);
                        return taskResult.rows();
                    }
                });
            }
        }));
    }

    private void traceLogResult(TaskResult taskResult) {
        if (LOGGER.isTraceEnabled()) {
            String result = Joiner.on(", ").join(Collections2.transform(Collections.singletonList(taskResult),
                new Function<TaskResult, String>() {
                    @Nullable
                    @Override
                    public String apply(@Nullable TaskResult input) {
                        if (input == null) {
                            return "";
                        } else {
                            return input.rows().toString();
                        }
                    }
                })
            );
            LOGGER.trace(String.format("received result: %s", result));
        }
    }

    @Override
    public List<ListenableFuture<TaskResult>> result() {
        return resultList;
    }

    @Override
    public void upstreamResult(List<ListenableFuture<TaskResult>> result) {
        upstreamResults = result;
    }
}
