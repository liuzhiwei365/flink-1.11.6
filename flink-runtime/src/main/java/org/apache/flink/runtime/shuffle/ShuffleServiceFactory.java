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

package org.apache.flink.runtime.shuffle;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.partition.consumer.IndexedInputGate;

/**
 * Interface for shuffle service factory implementations.
 *
 * <p>This component is a light-weight factory for {@link ShuffleMaster} and {@link
 * ShuffleEnvironment}.
 *
 * @param <SD> partition shuffle descriptor used for producer/consumer deployment and their data
 *     exchange.
 * @param <P> type of provided result partition writers
 * @param <G> type of provided input gates
 */

/**
 * flink 提供了基于netty 通信框架实现的 NettyShuffleServiceFactory,作为ShuffleServiceFactory接口的默认实现类
 * 定义了包含创建shuffleMaster 和 shuffleEnvironment 的方法
 * 默认实现 为 NettyShuffleMaster 和 NettyShuffleEnvironment
 *
 * shuffleMaster主要用于在jobMaster调度和执行Execution时，维护当前作业中的ResultPartition信息，例如ResourceId、
 * ExecutionAttemptId等。   紧接着会将jobMaster创建的NettyShuffleDescriptor的参数信息发送给对应的TaskExecutor实例，
 * 在TaskExecutor中就会基于此，通过ShuffleEnvironment组件创建ResultPartition、InputGate等组件
 *
 * */
public interface ShuffleServiceFactory<
        SD extends ShuffleDescriptor, P extends ResultPartitionWriter, G extends IndexedInputGate> {

    /**
     * Factory method to create a specific {@link ShuffleMaster} implementation.
     *
     * @param configuration Flink configuration
     * @return shuffle manager implementation
     */
    ShuffleMaster<SD> createShuffleMaster(Configuration configuration);

    /**
     * Factory method to create a specific local {@link ShuffleEnvironment} implementation.
     *
     * @param shuffleEnvironmentContext local context
     * @return local shuffle service environment implementation
     */
    ShuffleEnvironment<P, G> createShuffleEnvironment(
            ShuffleEnvironmentContext shuffleEnvironmentContext);
}
