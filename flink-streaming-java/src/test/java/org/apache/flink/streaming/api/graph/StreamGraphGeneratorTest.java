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

package org.apache.flink.streaming.api.graph;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.ConnectedStreams;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.IterativeStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.CoMapFunction;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.OutputTypeConfigurable;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.operators.TwoInputStreamOperator;
import org.apache.flink.streaming.api.transformations.MultipleInputTransformation;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.partitioner.BroadcastPartitioner;
import org.apache.flink.streaming.runtime.partitioner.GlobalPartitioner;
import org.apache.flink.streaming.runtime.partitioner.RebalancePartitioner;
import org.apache.flink.streaming.runtime.partitioner.ShufflePartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.util.EvenOddOutputSelector;
import org.apache.flink.streaming.util.NoOpIntMap;
import org.apache.flink.util.Collector;
import org.apache.flink.util.TestLogger;

import org.hamcrest.Description;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link StreamGraphGenerator}. This only tests correct translation of split/select,
 * union, partitioning since the other translation routines are tested already in operation specific
 * tests.
 */
@SuppressWarnings("serial")
public class StreamGraphGeneratorTest extends TestLogger {

    @Test
    public void generatorForwardsSavepointRestoreSettings() {
        StreamGraphGenerator streamGraphGenerator =
                new StreamGraphGenerator(
                        Collections.emptyList(), new ExecutionConfig(), new CheckpointConfig());

        streamGraphGenerator.setSavepointRestoreSettings(SavepointRestoreSettings.forPath("hello"));

        StreamGraph streamGraph = streamGraphGenerator.generate();
        assertThat(streamGraph.getSavepointRestoreSettings().getRestorePath(), is("hello"));
    }

    @Test
    public void testBufferTimeout() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.setBufferTimeout(77); // set timeout to some recognizable number

        env.fromElements(1, 2, 3, 4, 5)
                .map(value -> value)
                .setBufferTimeout(-1)
                .name("A")
                .map(value -> value)
                .setBufferTimeout(0)
                .name("B")
                .map(value -> value)
                .setBufferTimeout(12)
                .name("C")
                .map(value -> value)
                .name("D");

        final StreamGraph sg = env.getStreamGraph();
        for (StreamNode node : sg.getStreamNodes()) {
            switch (node.getOperatorName()) {
                case "A":
                    assertEquals(77L, node.getBufferTimeout());
                    break;
                case "B":
                    assertEquals(0L, node.getBufferTimeout());
                    break;
                case "C":
                    assertEquals(12L, node.getBufferTimeout());
                    break;
                case "D":
                    assertEquals(77L, node.getBufferTimeout());
                    break;
                default:
                    assertTrue(node.getOperator() instanceof StreamSource);
            }
        }
    }

    /**
     * This tests whether virtual Transformations behave correctly.
     *
     * <p>Verifies that partitioning, output selector, selected names are correctly set in the
     * StreamGraph when they are intermixed.
     */
    @Test
    public void testVirtualTransformations() throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Integer> source = env.fromElements(1, 10);

        DataStream<Integer> rebalanceMap = source.rebalance().map(new NoOpIntMap());

        // verify that only the partitioning that was set last is used
        DataStream<Integer> broadcastMap =
                rebalanceMap.forward().global().broadcast().map(new NoOpIntMap());

        broadcastMap.addSink(new DiscardingSink<>());

        // verify that partitioning is preserved across union and split/select
        EvenOddOutputSelector selector1 = new EvenOddOutputSelector();
        EvenOddOutputSelector selector2 = new EvenOddOutputSelector();
        EvenOddOutputSelector selector3 = new EvenOddOutputSelector();

        DataStream<Integer> map1Operator = rebalanceMap.map(new NoOpIntMap());

        DataStream<Integer> map1 = map1Operator.broadcast().split(selector1).select("even");

        DataStream<Integer> map2Operator = rebalanceMap.map(new NoOpIntMap());

        DataStream<Integer> map2 = map2Operator.split(selector2).select("odd").global();

        DataStream<Integer> map3Operator = rebalanceMap.map(new NoOpIntMap());

        DataStream<Integer> map3 = map3Operator.global().split(selector3).select("even").shuffle();

        SingleOutputStreamOperator<Integer> unionedMap =
                map1.union(map2).union(map3).map(new NoOpIntMap());

        unionedMap.addSink(new DiscardingSink<>());

        StreamGraph graph = env.getStreamGraph();

        // rebalanceMap
        assertTrue(
                graph.getStreamNode(rebalanceMap.getId()).getInEdges().get(0).getPartitioner()
                        instanceof RebalancePartitioner);

        // verify that only last partitioning takes precedence
        assertTrue(
                graph.getStreamNode(broadcastMap.getId()).getInEdges().get(0).getPartitioner()
                        instanceof BroadcastPartitioner);
        assertEquals(
                rebalanceMap.getId(),
                graph.getSourceVertex(graph.getStreamNode(broadcastMap.getId()).getInEdges().get(0))
                        .getId());

        // verify that partitioning in unions is preserved and that it works across split/select
        assertTrue(
                graph.getStreamNode(map1Operator.getId()).getOutEdges().get(0).getPartitioner()
                        instanceof BroadcastPartitioner);
        assertTrue(
                graph.getStreamNode(map1Operator.getId())
                        .getOutEdges()
                        .get(0)
                        .getSelectedNames()
                        .get(0)
                        .equals("even"));
        assertTrue(
                graph.getStreamNode(map1Operator.getId()).getOutputSelectors().contains(selector1));

        assertTrue(
                graph.getStreamNode(map2Operator.getId()).getOutEdges().get(0).getPartitioner()
                        instanceof GlobalPartitioner);
        assertTrue(
                graph.getStreamNode(map2Operator.getId())
                        .getOutEdges()
                        .get(0)
                        .getSelectedNames()
                        .get(0)
                        .equals("odd"));
        assertTrue(
                graph.getStreamNode(map2Operator.getId()).getOutputSelectors().contains(selector2));

        assertTrue(
                graph.getStreamNode(map3Operator.getId()).getOutEdges().get(0).getPartitioner()
                        instanceof ShufflePartitioner);
        assertTrue(
                graph.getStreamNode(map3Operator.getId())
                        .getOutEdges()
                        .get(0)
                        .getSelectedNames()
                        .get(0)
                        .equals("even"));
        assertTrue(
                graph.getStreamNode(map3Operator.getId()).getOutputSelectors().contains(selector3));
    }

    /**
     * This tests whether virtual Transformations behave correctly.
     *
     * <p>Checks whether output selector, partitioning works correctly when applied on a union.
     */
    @Test
    public void testVirtualTransformations2() throws Exception {

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Integer> source = env.fromElements(1, 10);

        DataStream<Integer> rebalanceMap = source.rebalance().map(new NoOpIntMap());

        DataStream<Integer> map1 = rebalanceMap.map(new NoOpIntMap());

        DataStream<Integer> map2 = rebalanceMap.map(new NoOpIntMap());

        DataStream<Integer> map3 = rebalanceMap.map(new NoOpIntMap());

        EvenOddOutputSelector selector = new EvenOddOutputSelector();

        SingleOutputStreamOperator<Integer> unionedMap =
                map1.union(map2)
                        .union(map3)
                        .broadcast()
                        .split(selector)
                        .select("foo")
                        .map(new NoOpIntMap());

        unionedMap.addSink(new DiscardingSink<>());

        StreamGraph graph = env.getStreamGraph();

        // verify that the properties are correctly set on all input operators
        assertTrue(
                graph.getStreamNode(map1.getId()).getOutEdges().get(0).getPartitioner()
                        instanceof BroadcastPartitioner);
        assertTrue(
                graph.getStreamNode(map1.getId())
                        .getOutEdges()
                        .get(0)
                        .getSelectedNames()
                        .get(0)
                        .equals("foo"));
        assertTrue(graph.getStreamNode(map1.getId()).getOutputSelectors().contains(selector));

        assertTrue(
                graph.getStreamNode(map2.getId()).getOutEdges().get(0).getPartitioner()
                        instanceof BroadcastPartitioner);
        assertTrue(
                graph.getStreamNode(map2.getId())
                        .getOutEdges()
                        .get(0)
                        .getSelectedNames()
                        .get(0)
                        .equals("foo"));
        assertTrue(graph.getStreamNode(map2.getId()).getOutputSelectors().contains(selector));

        assertTrue(
                graph.getStreamNode(map3.getId()).getOutEdges().get(0).getPartitioner()
                        instanceof BroadcastPartitioner);
        assertTrue(
                graph.getStreamNode(map3.getId())
                        .getOutEdges()
                        .get(0)
                        .getSelectedNames()
                        .get(0)
                        .equals("foo"));
        assertTrue(graph.getStreamNode(map3.getId()).getOutputSelectors().contains(selector));
    }

    /**
     * Test whether an {@link OutputTypeConfigurable} implementation gets called with the correct
     * output type. In this test case the output type must be BasicTypeInfo.INT_TYPE_INFO.
     */
    @Test
    public void testOutputTypeConfigurationWithOneInputTransformation() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Integer> source = env.fromElements(1, 10);

        OutputTypeConfigurableOperationWithOneInput outputTypeConfigurableOperation =
                new OutputTypeConfigurableOperationWithOneInput();

        DataStream<Integer> result =
                source.transform(
                        "Single input and output type configurable operation",
                        BasicTypeInfo.INT_TYPE_INFO,
                        outputTypeConfigurableOperation);

        result.addSink(new DiscardingSink<>());

        env.getStreamGraph();

        assertEquals(
                BasicTypeInfo.INT_TYPE_INFO, outputTypeConfigurableOperation.getTypeInformation());
    }

    @Test
    public void testOutputTypeConfigurationWithTwoInputTransformation() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Integer> source1 = env.fromElements(1, 10);
        DataStream<Integer> source2 = env.fromElements(2, 11);

        ConnectedStreams<Integer, Integer> connectedSource = source1.connect(source2);

        OutputTypeConfigurableOperationWithTwoInputs outputTypeConfigurableOperation =
                new OutputTypeConfigurableOperationWithTwoInputs();

        DataStream<Integer> result =
                connectedSource.transform(
                        "Two input and output type configurable operation",
                        BasicTypeInfo.INT_TYPE_INFO,
                        outputTypeConfigurableOperation);

        result.addSink(new DiscardingSink<>());

        env.getStreamGraph();

        assertEquals(
                BasicTypeInfo.INT_TYPE_INFO, outputTypeConfigurableOperation.getTypeInformation());
    }

    @Test
    public void testMultipleInputTransformation() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Integer> source1 = env.fromElements(1, 10);
        DataStream<Long> source2 = env.fromElements(2L, 11L);
        DataStream<String> source3 = env.fromElements("42", "44");

        MultipleInputTransformation<String> transform =
                new MultipleInputTransformation<String>(
                        "My Operator",
                        new MultipleInputOperatorFactory(),
                        BasicTypeInfo.STRING_TYPE_INFO,
                        3);

        env.addOperator(
                transform
                        .addInput(source1.getTransformation())
                        .addInput(source2.getTransformation())
                        .addInput(source3.getTransformation()));

        StreamGraph streamGraph = env.getStreamGraph();
        assertEquals(4, streamGraph.getStreamNodes().size());

        assertEquals(1, streamGraph.getStreamEdges(source1.getId(), transform.getId()).size());
        assertEquals(1, streamGraph.getStreamEdges(source2.getId(), transform.getId()).size());
        assertEquals(1, streamGraph.getStreamEdges(source3.getId(), transform.getId()).size());
        assertEquals(1, streamGraph.getStreamEdges(source1.getId()).size());
        assertEquals(1, streamGraph.getStreamEdges(source2.getId()).size());
        assertEquals(1, streamGraph.getStreamEdges(source3.getId()).size());
        assertEquals(0, streamGraph.getStreamEdges(transform.getId()).size());
    }

    @Test
    public void testUnalignedCheckpointDisabledOnBroadcast() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(42);

        DataStream<Long> source1 = env.fromElements(1L, 10L);
        DataStream<Long> map1 = source1.broadcast().map(l -> l);
        DataStream<Long> source2 = env.fromElements(2L, 11L);
        DataStream<Long> keyed = source2.keyBy(r -> 0L);

        final MapStateDescriptor<Long, Long> descriptor =
                new MapStateDescriptor<>(
                        "broadcast", BasicTypeInfo.LONG_TYPE_INFO, BasicTypeInfo.LONG_TYPE_INFO);
        final BroadcastStream<Long> broadcast = map1.broadcast(descriptor);
        final SingleOutputStreamOperator<Long> joined =
                keyed.connect(broadcast)
                        .process(
                                new KeyedBroadcastProcessFunction<Long, Long, Long, Long>() {
                                    @Override
                                    public void processElement(
                                            Long value, ReadOnlyContext ctx, Collector<Long> out) {}

                                    @Override
                                    public void processBroadcastElement(
                                            Long value, Context ctx, Collector<Long> out) {}
                                });

        StreamGraph streamGraph = env.getStreamGraph();
        assertEquals(4, streamGraph.getStreamNodes().size());

        // single broadcast
        assertThat(edge(streamGraph, source1, map1), supportsUnalignedCheckpoints(false));
        // keyed, connected with broadcast
        assertThat(edge(streamGraph, source2, joined), supportsUnalignedCheckpoints(false));
        // broadcast, connected with keyed
        assertThat(edge(streamGraph, map1, joined), supportsUnalignedCheckpoints(false));
    }

    private static StreamEdge edge(
            StreamGraph streamGraph, DataStream<Long> op1, DataStream<Long> op2) {
        List<StreamEdge> streamEdges = streamGraph.getStreamEdges(op1.getId(), op2.getId());
        assertThat(streamEdges, iterableWithSize(1));
        return streamEdges.get(0);
    }

    private static Matcher<StreamEdge> supportsUnalignedCheckpoints(boolean enabled) {
        return new FeatureMatcher<StreamEdge, Boolean>(
                equalTo(enabled),
                "supports unaligned checkpoint",
                "supports unaligned checkpoint") {
            @Override
            protected Boolean featureValueOf(StreamEdge actual) {
                return actual.supportsUnalignedCheckpoints();
            }
        };
    }

    /**
     * Tests that the KeyGroupStreamPartitioner are properly set up with the correct value of
     * maximum parallelism.
     */
    @Test
    public void testSetupOfKeyGroupPartitioner() {
        int maxParallelism = 42;
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.getConfig().setMaxParallelism(maxParallelism);

        DataStream<Integer> source = env.fromElements(1, 2, 3);

        DataStream<Integer> keyedResult = source.keyBy(value -> value).map(new NoOpIntMap());

        keyedResult.addSink(new DiscardingSink<>());

        StreamGraph graph = env.getStreamGraph();

        StreamNode keyedResultNode = graph.getStreamNode(keyedResult.getId());

        StreamPartitioner<?> streamPartitioner =
                keyedResultNode.getInEdges().get(0).getPartitioner();
    }

    /** Tests that the global and operator-wide max parallelism setting is respected. */
    @Test
    public void testMaxParallelismForwarding() {
        int globalMaxParallelism = 42;
        int keyedResult2MaxParallelism = 17;

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.getConfig().setMaxParallelism(globalMaxParallelism);

        DataStream<Integer> source = env.fromElements(1, 2, 3);

        DataStream<Integer> keyedResult1 = source.keyBy(value -> value).map(new NoOpIntMap());

        DataStream<Integer> keyedResult2 =
                keyedResult1
                        .keyBy(value -> value)
                        .map(new NoOpIntMap())
                        .setMaxParallelism(keyedResult2MaxParallelism);

        keyedResult2.addSink(new DiscardingSink<>());

        StreamGraph graph = env.getStreamGraph();

        StreamNode keyedResult1Node = graph.getStreamNode(keyedResult1.getId());
        StreamNode keyedResult2Node = graph.getStreamNode(keyedResult2.getId());

        assertEquals(globalMaxParallelism, keyedResult1Node.getMaxParallelism());
        assertEquals(keyedResult2MaxParallelism, keyedResult2Node.getMaxParallelism());
    }

    /**
     * Tests that the max parallelism is automatically set to the parallelism if it has not been
     * specified.
     */
    @Test
    public void testAutoMaxParallelism() {
        int globalParallelism = 42;
        int mapParallelism = 17;
        int maxParallelism = 21;
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(globalParallelism);

        DataStream<Integer> source = env.fromElements(1, 2, 3);

        DataStream<Integer> keyedResult1 = source.keyBy(value -> value).map(new NoOpIntMap());

        DataStream<Integer> keyedResult2 =
                keyedResult1
                        .keyBy(value -> value)
                        .map(new NoOpIntMap())
                        .setParallelism(mapParallelism);

        DataStream<Integer> keyedResult3 =
                keyedResult2
                        .keyBy(value -> value)
                        .map(new NoOpIntMap())
                        .setMaxParallelism(maxParallelism);

        DataStream<Integer> keyedResult4 =
                keyedResult3
                        .keyBy(value -> value)
                        .map(new NoOpIntMap())
                        .setMaxParallelism(maxParallelism)
                        .setParallelism(mapParallelism);

        keyedResult4.addSink(new DiscardingSink<>());

        StreamGraph graph = env.getStreamGraph();

        StreamNode keyedResult3Node = graph.getStreamNode(keyedResult3.getId());
        StreamNode keyedResult4Node = graph.getStreamNode(keyedResult4.getId());

        assertEquals(maxParallelism, keyedResult3Node.getMaxParallelism());
        assertEquals(maxParallelism, keyedResult4Node.getMaxParallelism());
    }

    /** Tests that the max parallelism is properly set for connected streams. */
    @Test
    public void testMaxParallelismWithConnectedKeyedStream() {
        int maxParallelism = 42;

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<Integer> input1 = env.fromElements(1, 2, 3, 4).setMaxParallelism(128);
        DataStream<Integer> input2 = env.fromElements(1, 2, 3, 4).setMaxParallelism(129);

        env.getConfig().setMaxParallelism(maxParallelism);

        DataStream<Integer> keyedResult =
                input1.connect(input2)
                        .keyBy(value -> value, value -> value)
                        .map(new NoOpIntCoMap());

        keyedResult.addSink(new DiscardingSink<>());

        StreamGraph graph = env.getStreamGraph();

        StreamNode keyedResultNode = graph.getStreamNode(keyedResult.getId());

        StreamPartitioner<?> streamPartitioner1 =
                keyedResultNode.getInEdges().get(0).getPartitioner();
        StreamPartitioner<?> streamPartitioner2 =
                keyedResultNode.getInEdges().get(1).getPartitioner();
    }

    /**
     * Tests that the json generated by JSONGenerator shall meet with 2 requirements: 1. sink nodes
     * are at the back 2. if both two nodes are sink nodes or neither of them is sink node, then
     * sort by its id.
     */
    @Test
    public void testSinkIdComparison() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<Integer> source = env.fromElements(1, 2, 3);
        for (int i = 0; i < 32; i++) {
            if (i % 2 == 0) {
                source.addSink(
                        new SinkFunction<Integer>() {
                            @Override
                            public void invoke(Integer value, Context ctx) throws Exception {}
                        });
            } else {
                source.map(x -> x + 1);
            }
        }
        // IllegalArgumentException will be thrown without FLINK-9216
        env.getStreamGraph().getStreamingPlanAsJSON();
    }

    /** Test iteration job, check slot sharing group and co-location group. */
    @Test
    public void testIteration() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Integer> source = env.fromElements(1, 2, 3).name("source");
        IterativeStream<Integer> iteration = source.iterate(3000);
        iteration.name("iteration").setParallelism(2);
        DataStream<Integer> map = iteration.map(x -> x + 1).name("map").setParallelism(2);
        DataStream<Integer> filter = map.filter((x) -> false).name("filter").setParallelism(2);
        iteration.closeWith(filter).print();

        final ResourceSpec resources = ResourceSpec.newBuilder(1.0, 100).build();
        iteration.getTransformation().setResources(resources, resources);

        StreamGraph streamGraph = env.getStreamGraph();
        for (Tuple2<StreamNode, StreamNode> iterationPair :
                streamGraph.getIterationSourceSinkPairs()) {
            assertNotNull(iterationPair.f0.getCoLocationGroup());
            assertEquals(
                    iterationPair.f0.getCoLocationGroup(), iterationPair.f1.getCoLocationGroup());

            assertEquals(
                    StreamGraphGenerator.DEFAULT_SLOT_SHARING_GROUP,
                    iterationPair.f0.getSlotSharingGroup());
            assertEquals(
                    iterationPair.f0.getSlotSharingGroup(), iterationPair.f1.getSlotSharingGroup());

            final ResourceSpec sourceMinResources = iterationPair.f0.getMinResources();
            final ResourceSpec sinkMinResources = iterationPair.f1.getMinResources();
            final ResourceSpec iterationResources = sourceMinResources.merge(sinkMinResources);
            assertThat(iterationResources, equalsResourceSpec(resources));
        }
    }

    private Matcher<ResourceSpec> equalsResourceSpec(ResourceSpec resources) {
        return new EqualsResourceSpecMatcher(resources);
    }

    /** Test slot sharing is enabled. */
    @Test
    public void testEnableSlotSharing() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<Integer> sourceDataStream = env.fromElements(1, 2, 3);
        DataStream<Integer> mapDataStream = sourceDataStream.map(x -> x + 1);

        final List<Transformation<?>> transformations = new ArrayList<>();
        transformations.add(sourceDataStream.getTransformation());
        transformations.add(mapDataStream.getTransformation());

        // all stream nodes share default group by default
        StreamGraph streamGraph =
                new StreamGraphGenerator(
                                transformations, env.getConfig(), env.getCheckpointConfig())
                        .generate();

        Collection<StreamNode> streamNodes = streamGraph.getStreamNodes();
        for (StreamNode streamNode : streamNodes) {
            assertEquals(
                    StreamGraphGenerator.DEFAULT_SLOT_SHARING_GROUP,
                    streamNode.getSlotSharingGroup());
        }
    }

    @Test
    public void testSetManagedMemoryWeight() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        final DataStream<Integer> source = env.fromElements(1, 2, 3).name("source");
        source.getTransformation().setManagedMemoryWeight(123);
        source.print().name("sink");

        final StreamGraph streamGraph = env.getStreamGraph();
        for (StreamNode streamNode : streamGraph.getStreamNodes()) {
            final int expectedWeight =
                    streamNode.getOperatorName().contains("source")
                            ? 123
                            : Transformation.DEFAULT_MANAGED_MEMORY_WEIGHT;
            assertEquals(expectedWeight, streamNode.getManagedMemoryWeight());
        }
    }

    private static class OutputTypeConfigurableOperationWithTwoInputs
            extends AbstractStreamOperator<Integer>
            implements TwoInputStreamOperator<Integer, Integer, Integer>,
                    OutputTypeConfigurable<Integer> {
        private static final long serialVersionUID = 1L;

        TypeInformation<Integer> tpeInformation;

        public TypeInformation<Integer> getTypeInformation() {
            return tpeInformation;
        }

        @Override
        public void setOutputType(
                TypeInformation<Integer> outTypeInfo, ExecutionConfig executionConfig) {
            tpeInformation = outTypeInfo;
        }

        @Override
        public void processElement1(StreamRecord<Integer> element) throws Exception {
            output.collect(element);
        }

        @Override
        public void processElement2(StreamRecord<Integer> element) throws Exception {
            output.collect(element);
        }

        @Override
        public void processWatermark1(Watermark mark) throws Exception {}

        @Override
        public void processWatermark2(Watermark mark) throws Exception {}

        @Override
        public void processLatencyMarker1(LatencyMarker latencyMarker) throws Exception {
            // ignore
        }

        @Override
        public void processLatencyMarker2(LatencyMarker latencyMarker) throws Exception {
            // ignore
        }

        @Override
        public void setup(
                StreamTask<?, ?> containingTask,
                StreamConfig config,
                Output<StreamRecord<Integer>> output) {}
    }

    private static class OutputTypeConfigurableOperationWithOneInput
            extends AbstractStreamOperator<Integer>
            implements OneInputStreamOperator<Integer, Integer>, OutputTypeConfigurable<Integer> {
        private static final long serialVersionUID = 1L;

        TypeInformation<Integer> tpeInformation;

        public TypeInformation<Integer> getTypeInformation() {
            return tpeInformation;
        }

        @Override
        public void processElement(StreamRecord<Integer> element) {
            output.collect(element);
        }

        @Override
        public void processWatermark(Watermark mark) {}

        @Override
        public void processLatencyMarker(LatencyMarker latencyMarker) throws Exception {}

        @Override
        public void setOutputType(
                TypeInformation<Integer> outTypeInfo, ExecutionConfig executionConfig) {
            tpeInformation = outTypeInfo;
        }
    }

    static class NoOpIntCoMap implements CoMapFunction<Integer, Integer, Integer> {
        private static final long serialVersionUID = 1886595528149124270L;

        public Integer map1(Integer value) throws Exception {
            return value;
        }

        public Integer map2(Integer value) throws Exception {
            return value;
        }
    }

    private static class EqualsResourceSpecMatcher extends TypeSafeMatcher<ResourceSpec> {
        private final ResourceSpec resources;

        EqualsResourceSpecMatcher(ResourceSpec resources) {
            this.resources = resources;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("expected resource spec ").appendValue(resources);
        }

        @Override
        protected boolean matchesSafely(ResourceSpec item) {
            return resources.lessThanOrEqual(item) && item.lessThanOrEqual(resources);
        }
    }

    private static class MultipleInputOperatorFactory implements StreamOperatorFactory<String> {
        @Override
        public <T extends StreamOperator<String>> T createStreamOperator(
                StreamOperatorParameters<String> parameters) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setChainingStrategy(ChainingStrategy strategy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChainingStrategy getChainingStrategy() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
            throw new UnsupportedOperationException();
        }
    }
}
