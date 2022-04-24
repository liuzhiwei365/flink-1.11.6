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

package org.apache.flink.runtime.io.network.netty;

import org.apache.flink.runtime.io.network.ConnectionID;
import org.apache.flink.runtime.io.network.ConnectionManager;
import org.apache.flink.runtime.io.network.PartitionRequestClient;
import org.apache.flink.runtime.io.network.TaskEventPublisher;
import org.apache.flink.runtime.io.network.partition.ResultPartitionProvider;

import java.io.IOException;

import static org.apache.flink.util.Preconditions.checkNotNull;

public class NettyConnectionManager implements ConnectionManager {

    private final NettyServer server;

    private final NettyClient client;

    private final NettyBufferPool bufferPool;

    private final PartitionRequestClientFactory partitionRequestClientFactory;

    private final NettyProtocol nettyProtocol;

    public NettyConnectionManager(
            ResultPartitionProvider partitionProvider,
            TaskEventPublisher taskEventPublisher,
            NettyConfig nettyConfig) {

        this.server = new NettyServer(nettyConfig);
        this.client = new NettyClient(nettyConfig);
        this.bufferPool = new NettyBufferPool(nettyConfig.getNumberOfArenas());

        this.partitionRequestClientFactory = new PartitionRequestClientFactory(client);

        this.nettyProtocol =
                new NettyProtocol(
                        checkNotNull(partitionProvider), checkNotNull(taskEventPublisher));
    }

    @Override
    public int start() throws IOException {
        /**一个TaskManager根本上只会存在一个NettyClient对象（对应的也只有一个Bootstrap实例）
         * 但一个TaskManager中的子任务实例很有可能会跟多个不同的远程TaskManager通信，所以，
         * 同一个Bootstrap实例可能会跟多个目标服务器建立连接，所以它是复用的，这一点不存在问题
         * 因为无论跟哪个目标服务器通信，Bootstrap的配置都是不变的。至于不同的RemoteChannel如何
         * 跟某个连接建立对应关系，这一点由PartitionRequestClientFactory来保证。
         *
         * 注：Netty自版本4.0.16开始，对于Linux系统提供原生的套接字通信传输支持
         * （也即，epoll机制，借助于JNI调用），这种传输机制拥有更高的性能
         * */
        client.init(nettyProtocol, bufferPool);
        /**NettyServer也会初始化Netty服务端的核心对象，除此之外它会启动对特定端口的侦听并准备接收客户端发起的请求
         * 服务端一定要绑定端口
         * */
        return server.init(nettyProtocol, bufferPool);
    }

    @Override
    public PartitionRequestClient createPartitionRequestClient(ConnectionID connectionId)
            throws IOException, InterruptedException {
        return partitionRequestClientFactory.createPartitionRequestClient(connectionId);
    }

    @Override
    public void closeOpenChannelConnections(ConnectionID connectionId) {
        partitionRequestClientFactory.closeOpenChannelConnections(connectionId);
    }

    @Override
    public int getNumberOfActiveConnections() {
        return partitionRequestClientFactory.getNumberOfActiveClients();
    }

    @Override
    public void shutdown() {
        client.shutdown();
        server.shutdown();
    }

    NettyClient getClient() {
        return client;
    }

    NettyServer getServer() {
        return server;
    }

    NettyBufferPool getBufferPool() {
        return bufferPool;
    }
}
