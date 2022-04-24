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
import org.apache.flink.runtime.io.network.NetworkClientHandler;
import org.apache.flink.runtime.io.network.PartitionRequestClient;
import org.apache.flink.runtime.io.network.netty.exception.LocalTransportException;
import org.apache.flink.runtime.io.network.netty.exception.RemoteTransportException;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;

import org.apache.flink.shaded.netty4.io.netty.channel.Channel;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelFuture;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelFutureListener;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for {@link NettyPartitionRequestClient} instances.
 *
 * <p>Instances of partition requests clients are shared among several {@link RemoteInputChannel}
 * instances.
 */
class PartitionRequestClientFactory {

    /**PartitionRequestClientFactory的构造器需要一个NettyClient；它使用ConcurrentHashMap在内存维护了
     * 一个ConnectionID与PartitionRequestClient或ConnectingChannel的映射关系；
     * ConnectingChannel实现了netty的ChannelFutureListener接口，
     * 它的operationComplete方法在ChannelFuture是success的时候会调用handInChannel方法，
     * 该方法会创建PartitionRequestClient；waitForChannel方法则会等待partitionRequestClient创建成功然后返回
     *
     */
    private final NettyClient nettyClient;

    //维护所有的连接对象
    private final ConcurrentMap<ConnectionID, Object> clients =
            new ConcurrentHashMap<ConnectionID, Object>();

    PartitionRequestClientFactory(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
    }

    /**
     * Atomically establishes a TCP connection to the given remote address and creates a {@link
     * NettyPartitionRequestClient} instance for this connection.
     *
     *  原子性地建立与远程的tcp连接，并交给clients ConcurrentHashMap对象管理
     *
     *  createPartitionRequestClient方法会先从ConcurrentHashMap查找是否有对应ConnectionID的PartitionRequestClient或ConnectingChannel，
     *  如果存在且是PartitionRequestClient实例则返回，如果存在且是ConnectingChannel实例则调用ConnectingChannel.waitForChannel等待PartitionRequestClient，
     *  然后替换对应ConnectionID在ConcurrentHashMap的值为PartitionRequestClient；如果ConcurrentHashMap没有对应ConnectionID的值，
     *  则会创建一个ConnectingChannel，然后放入到ConcurrentHashMap中，同时获取old object，如果old为null，则使用nettyClient.connect进行连接，
     *  然后获取PartitionRequestClient，之后替换ConcurrentHashMap中的值；如果old是ConnectingChannel则调用ConnectingChannel.waitForChannel等待PartitionRequestClient，
     *  然后替换ConcurrentHashMap中的值；在返回PartitionRequestClient之前会通过client.incrementReferenceCounter()来递增引用，
     *  如果递增不成功则调用destroyPartitionRequestClient，返回null，递增成功则返回PartitionRequestClient
     */
    NettyPartitionRequestClient createPartitionRequestClient(ConnectionID connectionId)
            throws IOException, InterruptedException {
        Object entry;
        NettyPartitionRequestClient client = null;

        while (client == null) {
            entry = clients.get(connectionId);

            if (entry != null) {
                // Existing channel or connecting channel
                if (entry instanceof NettyPartitionRequestClient) {
                    client = (NettyPartitionRequestClient) entry;
                } else {
                    ConnectingChannel future = (ConnectingChannel) entry;
                    client = future.waitForChannel();

                    clients.replace(connectionId, future, client);
                }
            } else {
                // No channel yet. Create one, but watch out for a race.
                // We create a "connecting future" and atomically add it to the map.
                // Only the thread that really added it establishes the channel.
                // The others need to wait on that original establisher's future.
                ConnectingChannel connectingChannel = new ConnectingChannel(connectionId, this);
                Object old = clients.putIfAbsent(connectionId, connectingChannel);

                if (old == null) {
                    ChannelFuture channelFuture;
                    try {
                        channelFuture = nettyClient.connect(connectionId.getAddress());
                    } catch (Exception e) {
                        // https://issues.apache.org/jira/browse/FLINK-18821
                        connectingChannel.notifyOfError(e);
                        throw new IOException(
                                "Connecting the channel failed: " + e.getMessage(), e);
                    }
                    channelFuture.addListener(connectingChannel);

                    client = connectingChannel.waitForChannel();

                    clients.replace(connectionId, connectingChannel, client);
                } else if (old instanceof ConnectingChannel) {
                    client = ((ConnectingChannel) old).waitForChannel();

                    clients.replace(connectionId, old, client);
                } else {
                    client = (NettyPartitionRequestClient) old;
                }
            }

            // Make sure to increment the reference count before handing a client
            // out to ensure correct bookkeeping for channel closing.
            if (!client.incrementReferenceCounter()) {
                destroyPartitionRequestClient(connectionId, client);
                client = null;
            }
        }

        return client;
    }

    public void closeOpenChannelConnections(ConnectionID connectionId) {
        Object entry = clients.get(connectionId);

        if (entry instanceof ConnectingChannel) {
            ConnectingChannel channel = (ConnectingChannel) entry;

            if (channel.dispose()) {
                clients.remove(connectionId, channel);
            }
        }
    }

    int getNumberOfActiveClients() {
        return clients.size();
    }

    /** Removes the client for the given {@link ConnectionID}. */
    void destroyPartitionRequestClient(ConnectionID connectionId, PartitionRequestClient client) {
        clients.remove(connectionId, client);
    }

    private static final class ConnectingChannel implements ChannelFutureListener {

        private final Object connectLock = new Object();

        private final ConnectionID connectionId;

        private final PartitionRequestClientFactory clientFactory;

        private boolean disposeRequestClient = false;

        public ConnectingChannel(
                ConnectionID connectionId, PartitionRequestClientFactory clientFactory) {
            this.connectionId = connectionId;
            this.clientFactory = clientFactory;
        }

        private boolean dispose() {
            boolean result;
            synchronized (connectLock) {
                if (partitionRequestClient != null) {
                    result = partitionRequestClient.disposeIfNotUsed();
                } else {
                    disposeRequestClient = true;
                    result = true;
                }

                connectLock.notifyAll();
            }

            return result;
        }

        private void handInChannel(Channel channel) {
            synchronized (connectLock) {
                try {
                    NetworkClientHandler clientHandler =
                            channel.pipeline().get(NetworkClientHandler.class);
                    partitionRequestClient =
                            new NettyPartitionRequestClient(
                                    channel, clientHandler, connectionId, clientFactory);

                    if (disposeRequestClient) {
                        partitionRequestClient.disposeIfNotUsed();
                    }

                    connectLock.notifyAll();
                } catch (Throwable t) {
                    notifyOfError(t);
                }
            }
        }

        private volatile NettyPartitionRequestClient partitionRequestClient;

        private volatile Throwable error;

        private NettyPartitionRequestClient waitForChannel()
                throws IOException, InterruptedException {
            synchronized (connectLock) {
                while (error == null && partitionRequestClient == null) {
                    connectLock.wait(2000);
                }
            }

            if (error != null) {
                throw new IOException(
                        "Connecting the channel failed: " + error.getMessage(), error);
            }

            return partitionRequestClient;
        }

        private void notifyOfError(Throwable error) {
            synchronized (connectLock) {
                this.error = error;
                connectLock.notifyAll();
            }
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
                handInChannel(future.channel());
            } else if (future.cause() != null) {
                notifyOfError(
                        new RemoteTransportException(
                                "Connecting to remote task manager + '"
                                        + connectionId.getAddress()
                                        + "' has failed. This might indicate that the remote task "
                                        + "manager has been lost.",
                                connectionId.getAddress(),
                                future.cause()));
            } else {
                notifyOfError(
                        new LocalTransportException(
                                String.format(
                                        "Connecting to remote task manager '%s' has been cancelled.",
                                        connectionId.getAddress()),
                                null));
            }
        }
    }
}
