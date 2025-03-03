/*
 * Copyright 2021-2023 Aklivity Inc.
 *
 * Aklivity licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.aklivity.zilla.runtime.binding.kafka.internal.stream;

import static io.aklivity.zilla.runtime.binding.kafka.internal.types.ProxyAddressProtocol.STREAM;
import static io.aklivity.zilla.runtime.engine.budget.BudgetCreditor.NO_BUDGET_ID;
import static io.aklivity.zilla.runtime.engine.budget.BudgetCreditor.NO_CREDITOR_INDEX;
import static io.aklivity.zilla.runtime.engine.budget.BudgetDebitor.NO_DEBITOR_INDEX;
import static io.aklivity.zilla.runtime.engine.buffer.BufferPool.NO_SLOT;
import static io.aklivity.zilla.runtime.engine.concurrent.Signaler.NO_CANCEL_ID;
import static java.lang.System.currentTimeMillis;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongLongConsumer;
import org.agrona.collections.MutableInteger;
import org.agrona.collections.Object2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;

import io.aklivity.zilla.runtime.binding.kafka.config.KafkaSaslConfig;
import io.aklivity.zilla.runtime.binding.kafka.config.KafkaServerConfig;
import io.aklivity.zilla.runtime.binding.kafka.internal.KafkaBinding;
import io.aklivity.zilla.runtime.binding.kafka.internal.KafkaConfiguration;
import io.aklivity.zilla.runtime.binding.kafka.internal.config.KafkaBindingConfig;
import io.aklivity.zilla.runtime.binding.kafka.internal.config.KafkaRouteConfig;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.Array32FW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.Flyweight;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.OctetsFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.String16FW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.RequestHeaderFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.ResponseHeaderFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.config.ConfigResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.config.DescribeConfigsRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.config.DescribeConfigsResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.config.ResourceRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.config.ResourceResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerAssignmentMetadataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerAssignmentTopicUserdataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerAssignmentTopicsUserdataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerAssignmentUserdataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerMetadataTopicFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerPartitionFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerSubscriptionMetadataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerSubscriptionUserdataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.consumer.ConsumerTopicPartitionFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.AssignmentFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.FindCoordinatorRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.FindCoordinatorResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.HeartbeatRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.HeartbeatResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.JoinGroupRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.JoinGroupResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.LeaveGroupRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.LeaveGroupResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.LeaveMemberRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.LeaveMemberResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.MemberMetadataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.ProtocolMetadataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.SyncGroupRequestFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.codec.group.SyncGroupResponseFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.rebalance.ConsumerAssignmentFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.rebalance.MemberAssignmentFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.rebalance.TopicAssignmentFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.rebalance.TopicPartitionFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.AbortFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.BeginFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.DataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.EndFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.ExtensionFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.FlushFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaBeginExFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaFlushExFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaGroupBeginExFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaGroupFlushExFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaGroupMemberFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaGroupMemberMetadataFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.KafkaResetExFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.ProxyBeginExFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.ResetFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.SignalFW;
import io.aklivity.zilla.runtime.binding.kafka.internal.types.stream.WindowFW;
import io.aklivity.zilla.runtime.engine.EngineContext;
import io.aklivity.zilla.runtime.engine.binding.BindingHandler;
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer;
import io.aklivity.zilla.runtime.engine.budget.BudgetDebitor;
import io.aklivity.zilla.runtime.engine.buffer.BufferPool;
import io.aklivity.zilla.runtime.engine.concurrent.Signaler;

public final class KafkaClientGroupFactory extends KafkaClientSaslHandshaker implements BindingHandler
{
    private static final int GROUP_RECORD_FRAME_MAX_SIZE = 256;
    private static final short METADATA_LOWEST_VERSION = 0;
    private static final short ERROR_EXISTS = -1;
    private static final short ERROR_NONE = 0;

    private static final short ERROR_COORDINATOR_LOAD_IN_PROGRESS = 14;
    private static final short ERROR_COORDINATOR_NOT_AVAILABLE = 15;
    private static final short ERROR_NOT_COORDINATOR_FOR_CONSUMER = 16;
    private static final short ERROR_UNKNOWN_MEMBER = 25;
    private static final short ERROR_MEMBER_ID_REQUIRED = 79;
    private static final short ERROR_REBALANCE_IN_PROGRESS = 27;
    private static final short SIGNAL_NEXT_REQUEST = 1;
    private static final short SIGNAL_SYNC_GROUP_REQUEST = 2;
    private static final short SIGNAL_HEARTBEAT_REQUEST = 3;
    private static final short DESCRIBE_CONFIGS_API_KEY = 32;
    private static final short DESCRIBE_CONFIGS_API_VERSION = 0;
    private static final byte RESOURCE_TYPE_BROKER = 4;
    private static final short FIND_COORDINATOR_API_KEY = 10;
    private static final short FIND_COORDINATOR_API_VERSION = 1;
    private static final short JOIN_GROUP_API_KEY = 11;
    private static final short JOIN_GROUP_VERSION = 5;
    private static final short SYNC_GROUP_API_KEY = 14;
    private static final short SYNC_GROUP_VERSION = 3;
    private static final short LEAVE_GROUP_API_KEY = 13;
    private static final short LEAVE_GROUP_VERSION = 3;
    private static final short HEARTBEAT_API_KEY = 12;
    private static final short HEARTBEAT_VERSION = 3;

    private static final String UNKNOWN_MEMBER_ID = "";
    private static final String HIGHLANDER_PROTOCOL = "highlander";
    private static final String GROUP_MIN_SESSION_TIMEOUT = "group.min.session.timeout.ms";
    private static final String GROUP_MAX_SESSION_TIMEOUT = "group.max.session.timeout.ms";
    private static final byte GROUP_KEY_TYPE = 0x00;
    private static final DirectBuffer EMPTY_BUFFER = new UnsafeBuffer();
    private static final OctetsFW EMPTY_OCTETS = new OctetsFW().wrap(EMPTY_BUFFER, 0, 0);
    private static final Consumer<OctetsFW.Builder> EMPTY_EXTENSION = ex -> {};

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final FlushFW flushRO = new FlushFW();
    private final AbortFW abortRO = new AbortFW();
    private final ResetFW resetRO = new ResetFW();
    private final WindowFW windowRO = new WindowFW();
    private final SignalFW signalRO = new SignalFW();
    private final ExtensionFW extensionRO = new ExtensionFW();
    private final KafkaBeginExFW kafkaBeginExRO = new KafkaBeginExFW();
    private final KafkaFlushExFW kafkaFlushExRO = new KafkaFlushExFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final FlushFW.Builder flushRW = new FlushFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();
    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final KafkaBeginExFW.Builder kafkaBeginExRW = new KafkaBeginExFW.Builder();
    private final KafkaFlushExFW.Builder kafkaFlushExRW = new KafkaFlushExFW.Builder();
    private final KafkaResetExFW.Builder kafkaResetExRW = new KafkaResetExFW.Builder();
    private final ProxyBeginExFW.Builder proxyBeginExRW = new ProxyBeginExFW.Builder();

    private final RequestHeaderFW.Builder requestHeaderRW = new RequestHeaderFW.Builder();
    private final DescribeConfigsRequestFW.Builder describeConfigsRequestRW = new DescribeConfigsRequestFW.Builder();
    private final ResourceRequestFW.Builder resourceRequestRW = new ResourceRequestFW.Builder();
    private final String16FW.Builder configNameRW = new String16FW.Builder(ByteOrder.BIG_ENDIAN);
    private final FindCoordinatorRequestFW.Builder findCoordinatorRequestRW = new FindCoordinatorRequestFW.Builder();
    private final JoinGroupRequestFW.Builder joinGroupRequestRW = new JoinGroupRequestFW.Builder();
    private final ProtocolMetadataFW.Builder protocolMetadataRW = new ProtocolMetadataFW.Builder();
    private final SyncGroupRequestFW.Builder syncGroupRequestRW = new SyncGroupRequestFW.Builder();
    private final AssignmentFW.Builder assignmentRW = new AssignmentFW.Builder();
    private final HeartbeatRequestFW.Builder heartbeatRequestRW = new HeartbeatRequestFW.Builder();
    private final LeaveGroupRequestFW.Builder leaveGroupRequestRW = new LeaveGroupRequestFW.Builder();
    private final LeaveMemberRequestFW.Builder leaveMemberRequestRW = new LeaveMemberRequestFW.Builder();
    private final ConsumerSubscriptionMetadataFW.Builder groupSubscriptionMetadataRW =
        new ConsumerSubscriptionMetadataFW.Builder();
    private final ConsumerAssignmentMetadataFW.Builder assignmentMetadataRW = new ConsumerAssignmentMetadataFW.Builder();
    private final ConsumerMetadataTopicFW.Builder metadataTopicRW = new ConsumerMetadataTopicFW.Builder();
    private final ConsumerTopicPartitionFW.Builder topicPartitionRW = new ConsumerTopicPartitionFW.Builder();
    private final ConsumerPartitionFW.Builder partitionRW = new ConsumerPartitionFW.Builder();
    private final ConsumerSubscriptionUserdataFW.Builder subscriptionUserdataRW =
        new ConsumerSubscriptionUserdataFW.Builder();
    private final ConsumerAssignmentUserdataFW.Builder assignmentUserdataRW =
        new ConsumerAssignmentUserdataFW.Builder();

    private final ConsumerAssignmentTopicsUserdataFW.Builder assignmentTopicsUserdataRW =
        new ConsumerAssignmentTopicsUserdataFW.Builder();
    private final Array32FW<MemberAssignmentFW> memberAssignmentRO =
        new Array32FW<>(new MemberAssignmentFW());

    private final ResourceResponseFW resourceResponseRO = new ResourceResponseFW();
    private final ConfigResponseFW configResponseRO = new ConfigResponseFW();
    private final ResponseHeaderFW responseHeaderRO = new ResponseHeaderFW();
    private final DescribeConfigsResponseFW describeConfigsResponseRO = new DescribeConfigsResponseFW();
    private final FindCoordinatorResponseFW findCoordinatorResponseRO = new FindCoordinatorResponseFW();
    private final JoinGroupResponseFW joinGroupResponseRO = new JoinGroupResponseFW();
    private final MemberMetadataFW memberMetadataRO = new MemberMetadataFW();
    private final SyncGroupResponseFW syncGroupResponseRO = new SyncGroupResponseFW();
    private final HeartbeatResponseFW heartbeatResponseRO = new HeartbeatResponseFW();
    private final LeaveGroupResponseFW leaveGroupResponseRO = new LeaveGroupResponseFW();
    private final LeaveMemberResponseFW leaveMemberResponseRO = new LeaveMemberResponseFW();
    private final Array32FW.Builder<TopicAssignmentFW.Builder, TopicAssignmentFW> topicPartitionsRW =
        new Array32FW.Builder<>(new TopicAssignmentFW.Builder(), new TopicAssignmentFW());
    private final ConsumerSubscriptionMetadataFW subscriptionMetadataRO = new ConsumerSubscriptionMetadataFW();
    private final ConsumerAssignmentMetadataFW assignmentMetadataRO = new ConsumerAssignmentMetadataFW();
    private final ConsumerMetadataTopicFW metadataTopicRO = new ConsumerMetadataTopicFW();
    private final ConsumerSubscriptionUserdataFW subscriptionUserdataRO = new ConsumerSubscriptionUserdataFW();
    private final ConsumerAssignmentUserdataFW assignmentUserdataRO = new ConsumerAssignmentUserdataFW();
    private final ConsumerTopicPartitionFW topicPartitionRO = new ConsumerTopicPartitionFW();
    private final ConsumerPartitionFW partitionRO = new ConsumerPartitionFW();
    private final Array32FW<ConsumerAssignmentFW> assignmentConsumersRO = new Array32FW<>(new ConsumerAssignmentFW());
    private final ConsumerAssignmentTopicsUserdataFW assignmentTopicsUserdataRO =
        new ConsumerAssignmentTopicsUserdataFW();
    private final KafkaGroupMemberMetadataFW kafkaMemberMetadataRO = new KafkaGroupMemberMetadataFW();

    private final KafkaDescribeClientDecoder decodeSaslHandshakeResponse = this::decodeSaslHandshakeResponse;
    private final KafkaDescribeClientDecoder decodeSaslHandshake = this::decodeSaslHandshake;
    private final KafkaDescribeClientDecoder decodeSaslHandshakeMechanisms = this::decodeSaslHandshakeMechanisms;
    private final KafkaDescribeClientDecoder decodeSaslHandshakeMechanism = this::decodeSaslHandshakeMechanism;
    private final KafkaDescribeClientDecoder decodeSaslAuthenticateResponse = this::decodeSaslAuthenticateResponse;
    private final KafkaDescribeClientDecoder decodeSaslAuthenticate = this::decodeSaslAuthenticate;
    private final KafkaDescribeClientDecoder decodeDescribeResponse = this::decodeDescribeResponse;
    private final KafkaDescribeClientDecoder decodeIgnoreAll = this::decodeIgnoreAll;
    private final KafkaDescribeClientDecoder decodeReject = this::decodeReject;
    private final KafkaGroupClusterClientDecoder decodeClusterSaslHandshakeResponse = this::decodeSaslHandshakeResponse;
    private final KafkaGroupClusterClientDecoder decodeClusterSaslHandshake = this::decodeSaslHandshake;
    private final KafkaGroupClusterClientDecoder decodeClusterSaslHandshakeMechanisms = this::decodeSaslHandshakeMechanisms;
    private final KafkaGroupClusterClientDecoder decodeClusterSaslHandshakeMechanism = this::decodeSaslHandshakeMechanism;
    private final KafkaGroupClusterClientDecoder decodeClusterSaslAuthenticateResponse = this::decodeSaslAuthenticateResponse;
    private final KafkaGroupClusterClientDecoder decodeClusterSaslAuthenticate = this::decodeSaslAuthenticate;
    private final KafkaGroupClusterClientDecoder decodeFindCoordinatorResponse = this::decodeFindCoordinatorResponse;
    private final KafkaGroupClusterClientDecoder decodeClusterReject = this::decodeClusterReject;
    private final KafkaGroupClusterClientDecoder decodeClusterIgnoreAll = this::decodeIgnoreAll;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorSaslHandshakeResponse =
        this::decodeSaslHandshakeResponse;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorSaslHandshake =
        this::decodeSaslHandshake;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorSaslHandshakeMechanisms =
        this::decodeSaslHandshakeMechanisms;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorSaslHandshakeMechanism =
        this::decodeSaslHandshakeMechanism;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorSaslAuthenticateResponse =
        this::decodeSaslAuthenticateResponse;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorSaslAuthenticate =
        this::decodeSaslAuthenticate;
    private final KafkaGroupCoordinatorClientDecoder decodeJoinGroupResponse =
        this::decodeJoinGroupResponse;
    private final KafkaGroupCoordinatorClientDecoder decodeSyncGroupResponse =
        this::decodeSyncGroupResponse;
    private final KafkaGroupCoordinatorClientDecoder decodeHeartbeatResponse =
        this::decodeHeartbeatResponse;
    private final KafkaGroupCoordinatorClientDecoder decodeLeaveGroupResponse =
        this::decodeLeaveGroupResponse;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorIgnoreAll = this::decodeIgnoreAll;
    private final KafkaGroupCoordinatorClientDecoder decodeCoordinatorReject = this::decodeCoordinatorReject;

    private final SecureRandom randomServerIdGenerator = new SecureRandom();

    private final int kafkaTypeId;
    private final int proxyTypeId;
    private final MutableDirectBuffer writeBuffer;
    private final MutableDirectBuffer extBuffer;
    private final MutableDirectBuffer userdataBuffer;
    private final BufferPool decodePool;
    private final BufferPool encodePool;
    private final Signaler signaler;
    private final BindingHandler streamFactory;
    private final UnaryOperator<KafkaSaslConfig> resolveSasl;
    private final LongFunction<KafkaClientRoute> supplyClientRoute;
    private final LongFunction<KafkaBindingConfig> supplyBinding;
    private final Supplier<String> supplyInstanceId;
    private final LongFunction<BudgetDebitor> supplyDebitor;
    private final Long2ObjectHashMap<GroupMembership> instanceIds;
    private final Object2ObjectHashMap<String, KafkaGroupStream> groupStreams;
    private final Map<String, String> configs;
    private final Duration rebalanceTimeout;
    private final String groupMinSessionTimeoutDefault;
    private final String groupMaxSessionTimeoutDefault;

    public KafkaClientGroupFactory(
        KafkaConfiguration config,
        EngineContext context,
        LongFunction<KafkaBindingConfig> supplyBinding,
        LongFunction<BudgetDebitor> supplyDebitor,
        Signaler signaler,
        BindingHandler streamFactory,
        UnaryOperator<KafkaSaslConfig> resolveSasl,
        LongFunction<KafkaClientRoute> supplyClientRoute)
    {
        super(config, context);
        this.rebalanceTimeout = config.clientGroupRebalanceTimeout();
        this.supplyInstanceId = config.clientInstanceIdSupplier();
        this.kafkaTypeId = context.supplyTypeId(KafkaBinding.NAME);
        this.proxyTypeId = context.supplyTypeId("proxy");
        this.writeBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.extBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.userdataBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.decodePool = context.bufferPool();
        this.encodePool = context.bufferPool();
        this.supplyBinding = supplyBinding;
        this.supplyDebitor = supplyDebitor;
        this.signaler = signaler;
        this.streamFactory = streamFactory;
        this.resolveSasl = resolveSasl;
        this.supplyClientRoute = supplyClientRoute;
        this.instanceIds = new Long2ObjectHashMap<>();
        this.groupStreams = new Object2ObjectHashMap<>();
        this.configs = new LinkedHashMap<>();
        this.groupMinSessionTimeoutDefault = String.valueOf(config.clientGroupMinSessionTimeoutDefault());
        this.groupMaxSessionTimeoutDefault = String.valueOf(config.clientGroupMaxSessionTimeoutDefault());
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer application)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long originId = begin.originId();
        final long routedId = begin.routedId();
        final long initialId = begin.streamId();
        final long affinity = begin.affinity();
        final long authorization = begin.authorization();
        final OctetsFW extension = begin.extension();
        final ExtensionFW beginEx = extensionRO.tryWrap(extension.buffer(), extension.offset(), extension.limit());
        final KafkaBeginExFW kafkaBeginEx = beginEx != null && beginEx.typeId() == kafkaTypeId ?
                kafkaBeginExRO.tryWrap(extension.buffer(), extension.offset(), extension.limit()) : null;

        assert kafkaBeginEx.kind() == KafkaBeginExFW.KIND_GROUP;
        final KafkaGroupBeginExFW kafkaGroupBeginEx = kafkaBeginEx.group();

        MessageConsumer newStream = null;

        final KafkaBindingConfig binding = supplyBinding.apply(routedId);
        final KafkaRouteConfig resolved;
        final int timeout = Math.min(kafkaGroupBeginEx.timeout(), 30_000);
        final String groupId = kafkaGroupBeginEx.groupId().asString();
        final String protocol = kafkaGroupBeginEx.protocol().asString();

        if (binding != null)
        {
            resolved = binding.resolve(authorization, null, groupId);

            if (resolved != null)
            {
                final long resolvedId = resolved.id;
                final KafkaSaslConfig sasl = resolveSasl.apply(binding.sasl());

                final GroupMembership groupMembership = instanceIds.get(binding.id);
                assert groupMembership != null;

                KafkaGroupStream group = groupStreams.get(groupId);

                if (group == null)
                {
                    KafkaGroupStream newGroup = new KafkaGroupStream(
                        application,
                        originId,
                        routedId,
                        initialId,
                        affinity,
                        resolvedId,
                        groupId,
                        protocol,
                        timeout,
                        groupMembership,
                        binding.servers(),
                        sasl);
                    newStream = newGroup::onStream;

                    groupStreams.put(groupId, newGroup);
                }
                else if (HIGHLANDER_PROTOCOL.equals(protocol))
                {
                    group.onStreamMigrate(begin, application);
                    newStream = group::onStream;
                }
            }
        }

        return newStream;
    }

    public void onAttached(
        long bindingId)
    {
        instanceIds.put(bindingId, new GroupMembership(supplyInstanceId.get()));
    }

    public void onDetached(
        long bindingId)
    {
        instanceIds.remove(bindingId);
    }

    private MessageConsumer newStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        Consumer<OctetsFW.Builder> extension)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .affinity(affinity)
                .extension(extension)
                .build();

        final MessageConsumer receiver =
                streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }

    private void doBegin(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        Flyweight extension)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doData(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer payload,
        int offset,
        int length,
        Consumer<OctetsFW.Builder> extension)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .reserved(reserved)
                .payload(payload, offset, length)
                .extension(extension)
                .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doDataEmpty(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        Consumer<OctetsFW.Builder> extension)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .reserved(reserved)
                .payload(EMPTY_OCTETS)
                .extension(extension)
                .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doFlush(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        Consumer<OctetsFW.Builder> extension)
    {
        final FlushFW flush = flushRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .budgetId(budgetId)
            .reserved(reserved)
            .extension(extension)
            .build();

        receiver.accept(flush.typeId(), flush.buffer(), flush.offset(), flush.sizeof());
    }

    private void doEnd(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        Consumer<OctetsFW.Builder> extension)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .extension(extension)
                .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doAbort(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        Consumer<OctetsFW.Builder> extension)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .extension(extension)
                .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doWindow(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int padding)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .padding(padding)
                .build();

        sender.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doReset(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        Flyweight extension)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .extension(extension.buffer(), extension.offset(), extension.sizeof())
                .build();

        sender.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    @FunctionalInterface
    private interface KafkaGroupClusterClientDecoder
    {
        int decode(
            ClusterClient client,
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int progress,
            int limit);
    }

    @FunctionalInterface
    private interface KafkaDescribeClientDecoder
    {
        int decode(
            DescribeClient client,
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int progress,
            int limit);
    }

    @FunctionalInterface
    private interface KafkaGroupCoordinatorClientDecoder
    {
        int decode(
            CoordinatorClient client,
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int progress,
            int limit);
    }

    private int decodeDescribeResponse(
        DescribeClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                break decode;
            }

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                progress = responseHeader.limit();

                final DescribeConfigsResponseFW describeConfigsResponse =
                    describeConfigsResponseRO.tryWrap(buffer, progress, limit);

                if (describeConfigsResponse == null)
                {
                    break decode;
                }

                progress = describeConfigsResponse.limit();

                final int resourceCount = describeConfigsResponse.resourceCount();
                for (int resourceIndex = 0; resourceIndex < resourceCount; resourceIndex++)
                {
                    final ResourceResponseFW resource = resourceResponseRO.tryWrap(buffer, progress, limit);
                    if (resource == null)
                    {
                        break decode;
                    }

                    progress = resource.limit();

                    final String resourceName = resource.name().asString();
                    final short errorCode = resource.errorCode();

                    if (errorCode != ERROR_NONE || !client.delegate.nodeId.equals(resourceName))
                    {
                        client.onDecodeResource(traceId, client.authorization, errorCode, resourceName);
                    }
                    else
                    {
                        final int configCount = resource.configCount();
                        configs.clear();
                        for (int configIndex = 0; configIndex < configCount; configIndex++)
                        {
                            final ConfigResponseFW config = configResponseRO.tryWrap(buffer, progress, limit);
                            if (config == null)
                            {
                                break decode;
                            }

                            progress = config.limit();

                            final String name = config.name().asString();
                            final String value = config.value().asString();

                            configs.put(name, value);
                        }

                        client.onDecodeDescribeResponse(traceId, configs);
                    }
                }
            }
        }

        return progress;
    }

    private int decodeReject(
        DescribeClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        client.doNetworkReset(traceId);
        client.decoder = decodeIgnoreAll;
        return limit;
    }

    private int decodeFindCoordinatorResponse(
        ClusterClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                break decode;
            }

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                progress = responseHeader.limit();

                final FindCoordinatorResponseFW findCoordinatorResponse =
                        findCoordinatorResponseRO.tryWrap(buffer, progress, limit);

                if (findCoordinatorResponse == null)
                {
                    break decode;
                }

                progress = findCoordinatorResponse.limit();

                final short errorCode = findCoordinatorResponse.errorCode();
                switch (errorCode)
                {
                case ERROR_COORDINATOR_LOAD_IN_PROGRESS:
                case ERROR_COORDINATOR_NOT_AVAILABLE:
                    client.onCoordinatorNotAvailable(traceId, authorization);
                    break;
                case ERROR_NONE:
                    client.onFindCoordinator(traceId, authorization, findCoordinatorResponse.nodeId(),
                        findCoordinatorResponse.host(), findCoordinatorResponse.port());
                    break;
                default:
                    client.errorCode = errorCode;
                    client.decoder = decodeClusterReject;
                    break;
                }

            }
        }

        return progress;
    }


    private int decodeClusterReject(
        ClusterClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        client.onNetworkError(traceId);
        client.decoder = decodeClusterIgnoreAll;
        return limit;
    }

    private int decodeCoordinatorReject(
        CoordinatorClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        client.onNetworkError(traceId);
        client.decoder = decodeCoordinatorIgnoreAll;
        return limit;
    }

    private int decodeIgnoreAll(
        KafkaSaslClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        return limit;
    }

    private int decodeJoinGroupResponse(
        CoordinatorClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                break decode;
            }

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                progress = responseHeader.limit();

                final JoinGroupResponseFW joinGroupResponse =
                    joinGroupResponseRO.tryWrap(buffer, progress, limit);

                final short errorCode = joinGroupResponse != null ? joinGroupResponse.errorCode() : ERROR_EXISTS;

                if (joinGroupResponse == null)
                {
                    break decode;
                }

                progress = joinGroupResponse.limit();

                switch (errorCode)
                {
                case ERROR_NOT_COORDINATOR_FOR_CONSUMER:
                    client.onNotCoordinatorError(traceId, authorization);
                    break;
                case ERROR_UNKNOWN_MEMBER:
                    client.onJoinGroupMemberIdError(traceId, authorization, UNKNOWN_MEMBER_ID);
                    break;
                case ERROR_MEMBER_ID_REQUIRED:
                    client.onJoinGroupMemberIdError(traceId, authorization,
                        joinGroupResponse.memberId().asString());
                    break;
                case ERROR_NONE:
                    client.members.clear();

                    client.generationId = joinGroupResponse.generatedId();

                    for (int i = 0; i < joinGroupResponse.memberCount(); i++)
                    {
                        final MemberMetadataFW memberMetadata = memberMetadataRO.tryWrap(buffer, progress, limit);
                        if (memberMetadata != null)
                        {
                            client.members.add(new MemberProtocol(
                                memberMetadata.memberId().asString(), memberMetadata.metadata()));
                            progress = memberMetadata.limit();
                        }
                        else
                        {
                            break decode;
                        }
                    }

                    client.onJoinGroupResponse(traceId, authorization, joinGroupResponse.leader().asString(),
                        joinGroupResponse.memberId().asString());
                    break;
                default:
                    client.errorCode = errorCode;
                    client.decoder = decodeCoordinatorReject;
                    break;
                }
            }
        }

        return progress;
    }

    private int decodeSyncGroupResponse(
        CoordinatorClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                break decode;
            }

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                progress = responseHeader.limit();

                final SyncGroupResponseFW syncGroupResponse =
                    syncGroupResponseRO.tryWrap(buffer, progress, limit);

                final short errorCode = syncGroupResponse != null ? syncGroupResponse.errorCode() : ERROR_EXISTS;

                if (syncGroupResponse == null)
                {
                    break decode;
                }

                progress = syncGroupResponse.limit();

                switch (errorCode)
                {
                case ERROR_REBALANCE_IN_PROGRESS:
                    client.onSyncGroupRebalance(traceId, authorization);
                    break;
                case ERROR_NONE:
                    client.onSyncGroupResponse(traceId, authorization, syncGroupResponse.assignment());
                    break;
                default:
                    client.errorCode = errorCode;
                    client.decoder = decodeCoordinatorReject;
                    break;
                }
            }
        }

        return progress;
    }

    private int decodeHeartbeatResponse(
        CoordinatorClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                break decode;
            }

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                progress = responseHeader.limit();

                final HeartbeatResponseFW heartbeatResponse =
                    heartbeatResponseRO.tryWrap(buffer, progress, limit);

                if (heartbeatResponse == null)
                {
                    break decode;
                }

                progress = heartbeatResponse.limit();

                final short errorCode = heartbeatResponse.errorCode();

                switch (errorCode)
                {
                case ERROR_UNKNOWN_MEMBER:
                    client.onJoinGroupMemberIdError(traceId, authorization, UNKNOWN_MEMBER_ID);
                    break;
                case ERROR_REBALANCE_IN_PROGRESS:
                    client.onRebalanceError(traceId, authorization);
                    break;
                case ERROR_NONE:
                    client.onHeartbeatResponse(traceId, authorization);
                    break;
                default:
                    client.errorCode = errorCode;
                    client.decoder = decodeCoordinatorReject;
                    break;
                }
            }
        }

        return progress;
    }

    private int decodeLeaveGroupResponse(
        CoordinatorClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                break decode;
            }

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                progress = responseHeader.limit();

                final LeaveGroupResponseFW leaveGroupResponse =
                    leaveGroupResponseRO.tryWrap(buffer, progress, limit);

                if (leaveGroupResponse == null)
                {
                    break decode;
                }

                progress = leaveGroupResponse.limit();

                final short errorCode = leaveGroupResponse.errorCode();
                if (errorCode == ERROR_NONE)
                {
                    members:
                    for (int i = 0; i < leaveGroupResponse.memberCount(); i++)
                    {
                        final LeaveMemberResponseFW member = leaveMemberResponseRO.tryWrap(buffer, progress, limit);
                        if (member != null)
                        {
                            final short memberErrorCode = member.errorCode();
                            if (memberErrorCode == ERROR_NONE)
                            {
                                progress = member.limit();
                            }
                            else
                            {
                                client.errorCode = errorCode;
                                client.decoder = decodeCoordinatorReject;
                            }
                        }
                        else
                        {
                            break members;
                        }
                    }

                    client.onLeaveGroupResponse(traceId, authorization);
                }
                else
                {
                    client.errorCode = errorCode;
                    client.decoder = decodeCoordinatorReject;
                }
            }
        }

        return progress;
    }

    private final class KafkaGroupStream
    {
        private final ClusterClient clusterClient;
        private final DescribeClient describeClient;
        private final CoordinatorClient coordinatorClient;
        private final GroupMembership groupMembership;
        private final List<KafkaServerConfig> servers;
        private final String groupId;
        private final String protocol;
        private final long resolvedId;
        private final int encodeMaxBytes;

        private MessageConsumer sender;
        private String host;
        private String nodeId;
        private int port;
        private int timeout;
        private MutableDirectBuffer metadataBuffer;

        private int state;

        private long originId;
        private long routedId;
        private long initialId;
        private long replyId;
        private long affinity;

        private long initialSeq;
        private long initialAck;
        private int initialMax;

        private long replySeq;
        private long replyAck;
        private int replyMax;
        private int replyPad;

        private long replyBudgetId;
        private int topicMetadataLimit;

        KafkaGroupStream(
            MessageConsumer sender,
            long originId,
            long routedId,
            long initialId,
            long affinity,
            long resolvedId,
            String groupId,
            String protocol,
            int timeout,
            GroupMembership groupMembership,
            List<KafkaServerConfig> servers,
            KafkaSaslConfig sasl)
        {
            this.sender = sender;
            this.originId = originId;
            this.routedId = routedId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.affinity = affinity;
            this.groupId = groupId;
            this.protocol = protocol;
            this.timeout = timeout;
            this.resolvedId = resolvedId;
            this.groupMembership = groupMembership;
            this.servers = servers;
            this.clusterClient = new ClusterClient(routedId, resolvedId, sasl, this);
            this.describeClient = new DescribeClient(routedId, resolvedId, sasl, this);
            this.coordinatorClient = new CoordinatorClient(routedId, resolvedId, sasl, this);
            this.metadataBuffer = new UnsafeBuffer(new byte[2048]);
            this.encodeMaxBytes = encodePool.slotCapacity() - GROUP_RECORD_FRAME_MAX_SIZE;
        }

        private void onStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onStreamBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onStreamData(data);
                break;
            case FlushFW.TYPE_ID:
                final FlushFW flush = flushRO.wrap(buffer, index, index + length);
                onStreamFlush(flush);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onStreamEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onStreamAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onStreamWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onStreamReset(reset);
                break;
            default:
                break;
            }
        }

        private void onStreamBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final OctetsFW extension = begin.extension();
            final ExtensionFW beginEx = extensionRO.tryWrap(extension.buffer(), extension.offset(), extension.limit());
            final KafkaBeginExFW kafkaBeginEx = beginEx != null && beginEx.typeId() == kafkaTypeId ?
                kafkaBeginExRO.tryWrap(extension.buffer(), extension.offset(), extension.limit()) : null;

            assert kafkaBeginEx.kind() == KafkaBeginExFW.KIND_GROUP;
            final KafkaGroupBeginExFW kafkaGroupBeginEx = kafkaBeginEx.group();

            OctetsFW metadata = kafkaGroupBeginEx.metadata();
            final int metadataSize = kafkaGroupBeginEx.metadataLen();

            if (metadataSize > 0)
            {
                metadataBuffer.putBytes(0, metadata.value(), 0, metadataSize);
                topicMetadataLimit += metadataSize;
            }

            state = KafkaState.openingInitial(state);

            clusterClient.doNetworkBeginIfNecessary(traceId, authorization, affinity);

            doStreamWindow(traceId, 0, encodeMaxBytes);
        }

        private void onStreamData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final long traceId = data.traceId();
            final int reserved = data.reserved();
            final OctetsFW payload = data.payload();

            assert acknowledge <= sequence;
            assert sequence >= initialSeq;

            initialSeq = sequence + reserved;

            assert initialAck <= initialSeq;

            if (initialSeq > initialAck + initialMax)
            {
                cleanupStream(traceId, ERROR_EXISTS);
                coordinatorClient.cleanupNetwork(traceId, authorization);
            }
            else
            {
                coordinatorClient.doSyncGroupRequest(traceId, budgetId, payload);
            }

            doStreamWindow(traceId, 0, encodeMaxBytes);
        }

        private void onStreamEnd(
            EndFW end)
        {
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            state = KafkaState.closingInitial(state);
            coordinatorClient.doLeaveGroupRequest(traceId);
        }

        private void onStreamFlush(
            FlushFW flush)
        {
            final long sequence = flush.sequence();
            final long acknowledge = flush.acknowledge();
            final long traceId = flush.traceId();
            final long budgetId = flush.budgetId();
            final int reserved = flush.reserved();
            final OctetsFW extension = flush.extension();

            assert acknowledge <= sequence;
            assert sequence >= initialSeq;

            initialSeq = sequence + reserved;
            initialAck = initialSeq;

            if (extension.sizeof() > 0)
            {
                final ExtensionFW beginEx = extensionRO.tryWrap(extension.buffer(), extension.offset(), extension.limit());
                final KafkaFlushExFW kafkaFlushEx = beginEx != null && beginEx.typeId() == kafkaTypeId ?
                    kafkaFlushExRO.tryWrap(extension.buffer(), extension.offset(), extension.limit()) : null;

                assert kafkaFlushEx.kind() == KafkaBeginExFW.KIND_GROUP;
                final KafkaGroupFlushExFW kafkaGroupFlushEx = kafkaFlushEx.group();

                Array32FW<KafkaGroupMemberFW> members = kafkaGroupFlushEx.members();

                assert members.fieldCount() == 1;

                members.forEach(m ->
                {
                    OctetsFW metadata = m.metadata();
                    final int metadataSize = m.metadataLen();

                    if (metadataSize > 0)
                    {
                        metadataBuffer.putBytes(0, metadata.value(), 0, metadataSize);
                        topicMetadataLimit = metadataSize;
                    }
                });

                if (host != null)
                {
                    coordinatorClient.doJoinGroupRequest(traceId);
                }
                else
                {
                    clusterClient.doEncodeRequestIfNecessary(traceId, budgetId);
                }
            }
            else
            {
                coordinatorClient.doHeartbeatRequest(traceId);
            }
        }

        private void onStreamAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            state = KafkaState.closedInitial(state);

            clusterClient.doNetworkAbort(traceId);
            describeClient.doNetworkAbort(traceId);
            coordinatorClient.doNetworkAbort(traceId);

            cleanupStream(traceId, ERROR_EXISTS);
        }

        private void onStreamWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();
            final long budgetId = window.budgetId();
            final int padding = window.padding();

            assert acknowledge <= sequence;
            assert sequence <= replySeq;
            assert acknowledge >= replyAck;
            assert maximum >= replyMax;

            this.replyAck = acknowledge;
            this.replyMax = maximum;
            this.replyPad = padding;
            this.replyBudgetId = budgetId;

            assert replyAck <= replySeq;
        }

        private void onStreamReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedReply(state);

            clusterClient.doNetworkReset(traceId);
            describeClient.doNetworkReset(traceId);
            coordinatorClient.doNetworkReset(traceId);
        }

        private boolean isStreamReplyOpen()
        {
            return KafkaState.replyOpening(state);
        }

        private void doStreamBeginIfNecessary(
            long traceId,
            long authorization)
        {
            if (!KafkaState.replyOpening(state))
            {
                doStreamBegin(traceId, authorization);
            }
        }

        private void doStreamBegin(
            long traceId,
            long authorization)
        {
            state = KafkaState.openingReply(state);

            final KafkaBeginExFW kafkaBeginEx =
                kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                    .typeId(kafkaTypeId)
                    .group(g -> g
                        .groupId(groupId)
                        .protocol(protocol)
                        .instanceId(groupMembership.instanceId)
                        .host(host)
                        .port(port)
                        .timeout(timeout))
                    .build();

            doBegin(sender, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, affinity, kafkaBeginEx);
        }

        private void doStreamData(
            long traceId,
            long authorization,
            DirectBuffer buffer,
            int offset,
            int length)
        {
            final int reserved = length + replyPad;

            if (length > 0)
            {
                doData(sender, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, replyBudgetId, reserved, buffer, offset, length, EMPTY_EXTENSION);
            }
            else
            {
                doDataEmpty(sender, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, replyBudgetId, reserved, EMPTY_EXTENSION);
            }

            replySeq += reserved;

            assert replyAck <= replySeq;
        }

        private void doStreamFlush(
            long traceId,
            long authorization,
            Consumer<OctetsFW.Builder> extension)
        {
            if (!KafkaState.replyClosed(state))
            {
                final int reserved = replyPad;

                doFlush(sender, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, replyBudgetId, reserved, extension);
            }
        }

        private void doStreamEnd(
            long traceId)
        {
            if (!KafkaState.replyClosed(state))
            {
                state = KafkaState.closedReply(state);
                doEnd(sender, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, 0, EMPTY_EXTENSION);
            }
        }

        private void doStreamAbort(
            long traceId)
        {
            if (!KafkaState.replyClosed(state))
            {
                state = KafkaState.closedReply(state);
                doAbort(sender, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, 0, EMPTY_EXTENSION);
            }
        }

        private void doStreamWindow(
            long traceId,
            int minInitialNoAck,
            int minInitialMax)
        {
            final long newInitialAck = Math.max(initialSeq - minInitialNoAck, initialAck);

            if (newInitialAck > initialAck || minInitialMax > initialMax || !KafkaState.initialOpened(state))
            {
                initialAck = newInitialAck;
                assert initialAck <= initialSeq;

                initialMax = minInitialMax;

                state = KafkaState.openedInitial(state);

                doWindow(sender, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, clusterClient.authorization, 0L, GROUP_RECORD_FRAME_MAX_SIZE);
            }
        }

        private void doStreamReset(
            long traceId,
            Flyweight extension)
        {
            state = KafkaState.closedInitial(state);

            doReset(sender, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, clusterClient.authorization, extension);
        }

        private void doStreamAbortIfNecessary(
            long traceId)
        {
            if (!KafkaState.replyClosed(state))
            {
                doStreamAbort(traceId);
            }
        }

        private void doStreamResetIfNecessary(
            long traceId,
            Flyweight extension)
        {
            if (!KafkaState.initialClosed(state))
            {
                doStreamReset(traceId, extension);
            }
        }

        private void onNotCoordinatorError(
            long traceId,
            long authorization)
        {
            clusterClient.doNetworkBeginIfNecessary(traceId, authorization, affinity);
        }

        private void onLeaveGroup(
            long traceId)
        {
            doStreamEnd(traceId);

            groupMembership.memberIds.remove(groupId);
            groupStreams.remove(groupId);
        }

        private void cleanupStream(
            long traceId,
            int error)
        {
            final KafkaResetExFW kafkaResetEx = kafkaResetExRW.wrap(writeBuffer,
                    ResetFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .error(error)
                .build();

            doStreamResetIfNecessary(traceId, kafkaResetEx);
            doStreamAbortIfNecessary(traceId);

            groupStreams.remove(groupId);
        }

        private void onStreamMigrate(
            BeginFW begin,
            MessageConsumer application)
        {
            final long originId = begin.originId();
            final long routedId = begin.routedId();
            final long initialId = begin.streamId();
            final long affinity = begin.affinity();
            final long traceId = begin.traceId();

            doStreamResetIfNecessary(traceId, EMPTY_OCTETS);
            doStreamAbortIfNecessary(traceId);

            this.sender = application;
            this.originId = originId;
            this.routedId = routedId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.affinity = affinity;

            if (KafkaState.closed(state))
            {
                initialSeq = 0;
                initialAck = 0;
                replyAck = 0;
                replySeq = 0;
                state = 0;
            }

            coordinatorClient.doJoinGroupRequest(traceId);
        }
    }

    private final class ClusterClient extends KafkaSaslClient
    {
        private final LongLongConsumer encodeSaslHandshakeRequest = this::doEncodeSaslHandshakeRequest;
        private final LongLongConsumer encodeSaslAuthenticateRequest = this::doEncodeSaslAuthenticateRequest;
        private final LongLongConsumer encodeFindCoordinatorRequest = this::doEncodeFindCoordinatorRequest;
        private final KafkaGroupStream delegate;

        private MessageConsumer network;

        private int state;
        private long authorization;

        private long initialSeq;
        private long initialAck;
        private int initialMin;
        private int initialMax;
        private int initialPad;
        private long initialBudgetId = NO_BUDGET_ID;
        private long initialDebIndex = NO_DEBITOR_INDEX;

        private long replySeq;
        private long replyAck;
        private long replyBud;
        private int replyMax;

        private int encodeSlot = NO_SLOT;
        private int encodeSlotOffset;

        private int decodeSlot = NO_SLOT;
        private int decodeSlotOffset;
        private int decodeSlotReserved;
        private short errorCode = ERROR_EXISTS;

        private int nextResponseId;

        private BudgetDebitor initialDeb;
        private KafkaGroupClusterClientDecoder decoder;
        private LongLongConsumer encoder;

        ClusterClient(
            long originId,
            long routedId,
            KafkaSaslConfig sasl,
            KafkaGroupStream delegate)
        {
            super(sasl, originId, routedId);

            this.encoder = sasl != null ? encodeSaslHandshakeRequest : encodeFindCoordinatorRequest;
            this.delegate = delegate;
            this.decoder = decodeClusterReject;
        }

        private void onNetwork(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onNetworkBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onNetworkData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onNetworkEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onNetworkAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onNetworkReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onNetworkWindow(window);
                break;
            case SignalFW.TYPE_ID:
                final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                onNetworkSignal(signal);
                break;
            default:
                break;
            }
        }

        private void onNetworkBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();

            authorization = begin.authorization();
            state = KafkaState.openingReply(state);

            doNetworkWindow(traceId, 0L, 0, 0, decodePool.slotCapacity());
        }

        private void onNetworkData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long budgetId = data.budgetId();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + data.reserved();
            authorization = data.authorization();
            replyBud = budgetId;

            assert replyAck <= replySeq;

            if (replySeq > replyAck + replyMax)
            {
                onNetworkError(traceId);
            }
            else
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = decodePool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    onNetworkError(traceId);
                }
                else
                {
                    final OctetsFW payload = data.payload();
                    int reserved = data.reserved();
                    int offset = payload.offset();
                    int limit = payload.limit();

                    final MutableDirectBuffer buffer = decodePool.buffer(decodeSlot);
                    buffer.putBytes(decodeSlotOffset, payload.buffer(), offset, limit - offset);
                    decodeSlotOffset += limit - offset;
                    decodeSlotReserved += reserved;

                    offset = 0;
                    limit = decodeSlotOffset;
                    reserved = decodeSlotReserved;

                    decodeNetwork(traceId, authorization, budgetId, reserved, buffer, offset, limit);
                }
            }
        }

        private void onNetworkEnd(
            EndFW end)
        {
            state = KafkaState.closedReply(state);

            cleanupDecodeSlotIfNecessary();
        }

        private void onNetworkAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            state = KafkaState.closedReply(state);

            onNetworkError(traceId);
        }

        private void onNetworkReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedInitial(state);

            onNetworkError(traceId);
        }

        private void onNetworkWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int minimum = window.minimum();
            final int maximum = window.maximum();
            final long traceId = window.traceId();
            final long budgetId = window.budgetId();
            final int padding = window.padding();

            assert acknowledge <= sequence;
            assert sequence <= initialSeq;
            assert acknowledge >= initialAck;
            assert maximum + acknowledge >= initialMax + initialAck;

            this.initialAck = acknowledge;
            this.initialMax = maximum;
            this.initialPad = padding;
            this.initialMin = minimum;
            this.initialBudgetId = budgetId;

            assert initialAck <= initialSeq;

            this.authorization = window.authorization();

            state = KafkaState.openedInitial(state);

            if (initialBudgetId != NO_BUDGET_ID && initialDebIndex == NO_DEBITOR_INDEX)
            {
                initialDeb = supplyDebitor.apply(initialBudgetId);
                initialDebIndex = initialDeb.acquire(initialBudgetId, initialId, this::doNetworkDataIfNecessary);
                assert initialDebIndex != NO_DEBITOR_INDEX;
            }

            doNetworkDataIfNecessary(budgetId);

            doEncodeRequestIfNecessary(traceId, budgetId);
        }

        private void doNetworkDataIfNecessary(
            long traceId)
        {
            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer buffer = encodePool.buffer(encodeSlot);
                final int limit = encodeSlotOffset;

                encodeNetwork(traceId, authorization, initialBudgetId, buffer, 0, limit);
            }
        }

        private void onNetworkSignal(
            SignalFW signal)
        {
            final long traceId = signal.traceId();
            final int signalId = signal.signalId();

            if (signalId == SIGNAL_NEXT_REQUEST)
            {
                doEncodeRequestIfNecessary(traceId, initialBudgetId);
            }
        }

        private void doNetworkBeginIfNecessary(
            long traceId,
            long authorization,
            long affinity)
        {
            if (KafkaState.closed(state))
            {
                initialSeq = 0;
                initialAck = 0;
                replyAck = 0;
                replySeq = 0;
                state = 0;
            }

            if (!KafkaState.initialOpening(state))
            {
                doNetworkBegin(traceId, authorization, affinity);
            }
        }

        private void doNetworkBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            assert state == 0;

            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);

            state = KafkaState.openingInitial(state);

            Consumer<OctetsFW.Builder> extension = EMPTY_EXTENSION;

            final KafkaServerConfig kafkaServerConfig =
                delegate.servers != null ?
                    delegate.servers.get(randomServerIdGenerator.nextInt(delegate.servers.size())) : null;

            if (kafkaServerConfig != null)
            {
                extension =  e -> e.set((b, o, l) -> proxyBeginExRW.wrap(b, o, l)
                    .typeId(proxyTypeId)
                    .address(a -> a.inet(i -> i.protocol(p -> p.set(STREAM))
                        .source("0.0.0.0")
                        .destination(kafkaServerConfig.host)
                        .sourcePort(0)
                        .destinationPort(kafkaServerConfig.port)))
                    .infos(i -> i.item(ii -> ii.authority(kafkaServerConfig.host)))
                    .build()
                    .sizeof());
            }

            network = newStream(this::onNetwork, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, extension);
        }

        @Override
        protected void doNetworkData(
            long traceId,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                encodeBuffer.putBytes(encodeSlotOffset, buffer, offset, limit - offset);
                encodeSlotOffset += limit - offset;

                buffer = encodeBuffer;
                offset = 0;
                limit = encodeSlotOffset;
            }

            encodeNetwork(traceId, authorization, budgetId, buffer, offset, limit);
        }

        private void doNetworkEnd(
            long traceId,
            long authorization)
        {
            if (!KafkaState.initialClosed(state))
            {
                state = KafkaState.closedInitial(state);

                doEnd(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, EMPTY_EXTENSION);
            }

            cleanupEncodeSlotIfNecessary();
            cleanupBudgetIfNecessary();
        }

        private void doNetworkAbort(
            long traceId)
        {
            if (!KafkaState.initialClosed(state))
            {
                doAbort(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, EMPTY_EXTENSION);
                state = KafkaState.closedInitial(state);
            }

            cleanupEncodeSlotIfNecessary();
            cleanupBudgetIfNecessary();
        }

        private void doNetworkReset(
            long traceId)
        {
            if (!KafkaState.replyClosed(state))
            {
                doReset(network, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, EMPTY_OCTETS);
                state = KafkaState.closedReply(state);
            }

            cleanupDecodeSlotIfNecessary();
        }

        private void doNetworkWindow(
            long traceId,
            long budgetId,
            int minReplyNoAck,
            int minReplyPad,
            int minReplyMax)
        {
            final long newReplyAck = Math.max(replySeq - minReplyNoAck, replyAck);

            if (newReplyAck > replyAck || minReplyMax > replyMax || !KafkaState.replyOpened(state))
            {
                replyAck = newReplyAck;
                assert replyAck <= replySeq;

                replyMax = minReplyMax;

                state = KafkaState.openedReply(state);

                doWindow(network, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, budgetId, minReplyPad);
            }
        }

        private void doEncodeRequestIfNecessary(
            long traceId,
            long budgetId)
        {
            if (nextRequestId == nextResponseId)
            {
                encoder.accept(traceId, budgetId);
            }
        }

        private void doEncodeFindCoordinatorRequest(
            long traceId,
            long budgetId)
        {
            final MutableDirectBuffer encodeBuffer = writeBuffer;
            final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
            final int encodeLimit = encodeBuffer.capacity();

            int encodeProgress = encodeOffset;

            final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .length(0)
                .apiKey(FIND_COORDINATOR_API_KEY)
                .apiVersion(FIND_COORDINATOR_API_VERSION)
                .correlationId(0)
                .clientId(clientId)
                .build();

            encodeProgress = requestHeader.limit();

            final FindCoordinatorRequestFW findCoordinatorRequest =
                findCoordinatorRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .key(delegate.groupId)
                    .keyType(GROUP_KEY_TYPE)
                    .build();

            encodeProgress = findCoordinatorRequest.limit();

            final int requestId = nextRequestId++;
            final int requestSize = encodeProgress - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

            requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                .length(requestSize)
                .apiKey(requestHeader.apiKey())
                .apiVersion(requestHeader.apiVersion())
                .correlationId(requestId)
                .clientId(requestHeader.clientId())
                .build();

            doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress);

            decoder = decodeFindCoordinatorResponse;
        }

        private void encodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            final int length = limit - offset;
            final int initialBudget = Math.max(initialMax - (int)(initialSeq - initialAck), 0);
            final int reservedMax = Math.max(Math.min(length + initialPad, initialBudget), initialMin);

            int reserved = reservedMax;

            flush:
            if (reserved > 0)
            {

                boolean claimed = false;

                if (initialDebIndex != NO_DEBITOR_INDEX)
                {
                    reserved = initialDeb.claim(traceId, initialDebIndex, initialId, reservedMax, reservedMax, 0);
                    claimed = reserved > 0;
                }

                if (reserved < initialPad || reserved == initialPad && length > 0)
                {
                    break flush;
                }

                doData(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, budgetId, reserved, buffer, offset, length, EMPTY_EXTENSION);

                initialSeq += reserved;

                assert initialAck <= initialSeq;
            }

            final int flushed = Math.max(reserved - initialPad, 0);
            final int remaining = length - flushed;
            if (remaining > 0)
            {
                if (encodeSlot == NO_SLOT)
                {
                    encodeSlot = encodePool.acquire(initialId);
                }

                if (encodeSlot == NO_SLOT)
                {
                    onNetworkError(traceId);
                }
                else
                {
                    final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                    encodeBuffer.putBytes(0, buffer, offset + flushed, remaining);
                    encodeSlotOffset = remaining;
                }
            }
            else
            {
                cleanupEncodeSlotIfNecessary();
            }
        }

        private void decodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int limit)
        {
            KafkaGroupClusterClientDecoder previous = null;
            int progress = offset;
            while (progress <= limit && previous != decoder)
            {
                previous = decoder;
                progress = decoder.decode(this, traceId, authorization, budgetId, reserved, buffer, offset, progress, limit);
            }

            if (progress < limit)
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = decodePool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    onNetworkError(traceId);
                }
                else
                {
                    final MutableDirectBuffer decodeBuffer = decodePool.buffer(decodeSlot);
                    decodeBuffer.putBytes(0, buffer, progress, limit - progress);
                    decodeSlotOffset = limit - progress;
                    decodeSlotReserved = (limit - progress) * reserved / (limit - offset);
                }

                doNetworkWindow(traceId, budgetId, decodeSlotOffset, 0, replyMax);
            }
            else
            {
                cleanupDecodeSlotIfNecessary();

                if (reserved > 0)
                {
                    doNetworkWindow(traceId, budgetId, 0, 0, replyMax);
                }
            }
        }

        @Override
        protected void doDecodeSaslHandshakeResponse(
            long traceId)
        {
            decoder = decodeClusterSaslHandshakeResponse;
        }

        @Override
        protected void doDecodeSaslHandshake(
            long traceId)
        {
            decoder = decodeClusterSaslHandshake;
        }

        @Override
        protected void doDecodeSaslHandshakeMechanisms(
            long traceId)
        {
            decoder = decodeClusterSaslHandshakeMechanisms;
        }

        @Override
        protected void doDecodeSaslHandshakeMechansim(
            long traceId)
        {
            decoder = decodeClusterSaslHandshakeMechanism;
        }

        @Override
        protected void doDecodeSaslAuthenticateResponse(
            long traceId)
        {
            decoder = decodeClusterSaslAuthenticateResponse;
        }

        @Override
        protected void doDecodeSaslAuthenticate(
            long traceId)
        {
            decoder = decodeClusterSaslAuthenticate;
        }

        @Override
        protected void onDecodeSaslHandshakeResponse(
            long traceId,
            long authorization,
            int errorCode)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                encoder = encodeSaslAuthenticateRequest;
                decoder = decodeClusterSaslAuthenticateResponse;
                break;
            default:
                delegate.cleanupStream(traceId, errorCode);
                doNetworkEnd(traceId, authorization);
                break;
            }
        }

        @Override
        protected void onDecodeSaslAuthenticateResponse(
            long traceId,
            long authorization,
            int errorCode)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                encoder = encodeFindCoordinatorRequest;
                decoder = decodeFindCoordinatorResponse;
                break;
            default:
                delegate.cleanupStream(traceId, errorCode);
                doNetworkEnd(traceId, authorization);
                break;
            }
        }

        @Override
        protected void onDecodeSaslResponse(
            long traceId)
        {
            nextResponseId++;
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void onCoordinatorNotAvailable(
            long traceId,
            long authorization)
        {
            nextResponseId++;

            encoder = encodeFindCoordinatorRequest;
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void onFindCoordinator(
            long traceId,
            long authorization,
            int nodeId,
            String16FW host,
            int port)
        {
            nextResponseId++;

            delegate.nodeId = String.valueOf(nodeId);
            delegate.host = host.asString();
            delegate.port = port;

            delegate.describeClient.doNetworkBegin(traceId, authorization, 0);

            cleanupNetwork(traceId, authorization);
        }

        private void cleanupNetwork(
            long traceId,
            long authorization)
        {
            doNetworkEnd(traceId, authorization);
            doNetworkReset(traceId);
        }

        private void onNetworkError(
            long traceId)
        {
            doNetworkAbort(traceId);
            doNetworkReset(traceId);

            delegate.cleanupStream(traceId, errorCode);
        }

        private void cleanupDecodeSlotIfNecessary()
        {
            if (decodeSlot != NO_SLOT)
            {
                decodePool.release(decodeSlot);
                decodeSlot = NO_SLOT;
                decodeSlotOffset = 0;
                decodeSlotReserved = 0;
            }
        }

        private void cleanupEncodeSlotIfNecessary()
        {
            if (encodeSlot != NO_SLOT)
            {
                encodePool.release(encodeSlot);
                encodeSlot = NO_SLOT;
                encodeSlotOffset = 0;
            }
        }

        private void cleanupBudgetIfNecessary()
        {
            if (initialDebIndex != NO_DEBITOR_INDEX)
            {
                initialDeb.release(initialDebIndex, initialId);
                initialDebIndex = NO_DEBITOR_INDEX;
            }
        }
    }

    private final class DescribeClient extends KafkaSaslClient
    {
        private final LongLongConsumer encodeSaslHandshakeRequest = this::doEncodeSaslHandshakeRequest;
        private final LongLongConsumer encodeSaslAuthenticateRequest = this::doEncodeSaslAuthenticateRequest;
        private final LongLongConsumer encodeDescribeRequest = this::doEncodeDescribeRequest;

        private MessageConsumer network;
        private final Map<String, String> configs;
        private final KafkaGroupStream delegate;

        private int state;
        private long authorization;

        private long initialSeq;
        private long initialAck;
        private int initialMin;
        private int initialMax;
        private int initialPad;
        private long initialBudgetId = NO_BUDGET_ID;
        private long initialDebIndex = NO_CREDITOR_INDEX;

        private long replySeq;
        private long replyAck;
        private int replyMax;

        private int encodeSlot = NO_SLOT;
        private int encodeSlotOffset;
        private long encodeSlotTraceId;

        private int decodeSlot = NO_SLOT;
        private int decodeSlotOffset;
        private int decodeSlotReserved;

        private int nextResponseId;

        private KafkaDescribeClientDecoder decoder;
        private LongLongConsumer encoder;
        private BudgetDebitor initialDeb;

        DescribeClient(
            long originId,
            long routedId,
            KafkaSaslConfig sasl,
            KafkaGroupStream delegate)
        {
            super(sasl, originId, routedId);
            this.configs = new LinkedHashMap<>();
            this.delegate = delegate;

            this.encoder = sasl != null ? encodeSaslHandshakeRequest : encodeDescribeRequest;
            this.decoder = decodeReject;

            this.configs.put(GROUP_MIN_SESSION_TIMEOUT, null);
            this.configs.put(GROUP_MAX_SESSION_TIMEOUT, null);
        }

        public void onDecodeResource(
            long traceId,
            long authorization,
            short errorCode,
            String resource)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                assert resource.equals(delegate.nodeId);
                break;
            default:
                onNetworkError(traceId, errorCode);
                break;
            }
        }

        private void onNetwork(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onNetworkBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onNetworkData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onNetworkEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onNetworkAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onNetworkReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onNetworkWindow(window);
                break;
            case SignalFW.TYPE_ID:
                final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                onNetworkSignal(signal);
                break;
            default:
                break;
            }
        }

        private void onNetworkBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();

            authorization = begin.authorization();
            state = KafkaState.openingReply(state);

            doNetworkWindow(traceId, 0L, 0, 0, decodePool.slotCapacity());
        }

        private void onNetworkData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long budgetId = data.budgetId();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + data.reserved();
            authorization = data.authorization();

            assert replyAck <= replySeq;

            if (replySeq > replyAck + replyMax)
            {
                cleanupNetwork(traceId);
            }
            else
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = decodePool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    cleanupNetwork(traceId);
                }
                else
                {
                    final OctetsFW payload = data.payload();
                    int reserved = data.reserved();
                    int offset = payload.offset();
                    int limit = payload.limit();

                    final MutableDirectBuffer buffer = decodePool.buffer(decodeSlot);
                    buffer.putBytes(decodeSlotOffset, payload.buffer(), offset, limit - offset);
                    decodeSlotOffset += limit - offset;
                    decodeSlotReserved += reserved;

                    offset = 0;
                    limit = decodeSlotOffset;
                    reserved = decodeSlotReserved;

                    decodeNetwork(traceId, authorization, budgetId, reserved, buffer, offset, limit);
                }
            }
        }

        private void onNetworkEnd(
            EndFW end)
        {
            state = KafkaState.closedReply(state);

            cleanupDecodeSlotIfNecessary();
        }

        private void onNetworkAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            state = KafkaState.closedReply(state);

            cleanupNetwork(traceId);
        }

        private void onNetworkReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedInitial(state);

            cleanupNetwork(traceId);
        }

        private void onNetworkWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int minimum = window.minimum();
            final int maximum = window.maximum();
            final long traceId = window.traceId();
            final long budgetId = window.budgetId();
            final int padding = window.padding();

            assert acknowledge <= sequence;
            assert sequence <= initialSeq;
            assert acknowledge >= initialAck;
            assert maximum + acknowledge >= initialMax + initialAck;

            this.initialAck = acknowledge;
            this.initialMax = maximum;
            this.initialPad = padding;
            this.initialMin = minimum;
            this.initialBudgetId = budgetId;

            assert initialAck <= initialSeq;

            this.authorization = window.authorization();

            state = KafkaState.openedInitial(state);

            if (initialBudgetId != NO_BUDGET_ID && initialDebIndex == NO_DEBITOR_INDEX)
            {
                initialDeb = supplyDebitor.apply(initialBudgetId);
                initialDebIndex = initialDeb.acquire(initialBudgetId, initialId, this::doNetworkDataIfNecessary);
                assert initialDebIndex != NO_DEBITOR_INDEX;
            }

            doNetworkDataIfNecessary(budgetId);

            doEncodeRequestIfNecessary(traceId, budgetId);
        }

        private void doNetworkDataIfNecessary(
            long budgetId)
        {
            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer buffer = encodePool.buffer(encodeSlot);
                final int limit = encodeSlotOffset;

                encodeNetwork(encodeSlotTraceId, authorization, budgetId, buffer, 0, limit);
            }
        }

        private void onNetworkSignal(
            SignalFW signal)
        {
            final long traceId = signal.traceId();
            final int signalId = signal.signalId();

            if (signalId == SIGNAL_NEXT_REQUEST)
            {
                doEncodeRequestIfNecessary(traceId, initialBudgetId);
            }
        }

        private void doNetworkBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            if (KafkaState.closed(state))
            {
                initialSeq = 0;
                initialAck = 0;
                replyAck = 0;
                replySeq = 0;
                state = 0;
            }

            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);

            state = KafkaState.openingInitial(state);

            Consumer<OctetsFW.Builder> extension = EMPTY_EXTENSION;

            final KafkaClientRoute clientRoute = supplyClientRoute.apply(routedId);
            final KafkaBrokerInfo broker = clientRoute.brokers.get(Long.parseLong(delegate.nodeId));

            if (broker != null)
            {
                extension = e -> e.set((b, o, l) -> proxyBeginExRW.wrap(b, o, l)
                    .typeId(proxyTypeId)
                    .address(a -> a.inet(i -> i.protocol(p -> p.set(STREAM))
                        .source("0.0.0.0")
                        .destination(broker.host)
                        .sourcePort(0)
                        .destinationPort(broker.port)))
                    .infos(i -> i.item(ii -> ii.authority(broker.host)))
                    .build()
                    .sizeof());
            }

            network = newStream(this::onNetwork, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, extension);
        }

        @Override
        protected void doNetworkData(
            long traceId,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                encodeBuffer.putBytes(encodeSlotOffset, buffer, offset, limit - offset);
                encodeSlotOffset += limit - offset;
                encodeSlotTraceId = traceId;

                buffer = encodeBuffer;
                offset = 0;
                limit = encodeSlotOffset;
            }

            encodeNetwork(traceId, authorization, budgetId, buffer, offset, limit);
        }

        private void doNetworkEnd(
            long traceId,
            long authorization)
        {
            state = KafkaState.closedInitial(state);

            cleanupEncodeSlotIfNecessary();
            cleanupBudgetIfNecessary();

            doEnd(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, EMPTY_EXTENSION);
        }

        private void doNetworkAbort(
            long traceId)
        {
            if (KafkaState.initialOpening(state) && !KafkaState.initialClosed(state))
            {
                doAbort(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, EMPTY_EXTENSION);
                state = KafkaState.closedInitial(state);
            }

            cleanupEncodeSlotIfNecessary();
            cleanupBudgetIfNecessary();
        }

        private void doNetworkReset(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doReset(network, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, EMPTY_OCTETS);
                state = KafkaState.closedReply(state);
            }

            cleanupDecodeSlotIfNecessary();
        }

        private void doNetworkWindow(
            long traceId,
            long budgetId,
            int minReplyNoAck,
            int minReplyPad,
            int minReplyMax)
        {
            final long newReplyAck = Math.max(replySeq - minReplyNoAck, replyAck);

            if (newReplyAck > replyAck || minReplyMax > replyMax || !KafkaState.replyOpened(state))
            {
                replyAck = newReplyAck;
                assert replyAck <= replySeq;

                replyMax = minReplyMax;

                state = KafkaState.openedReply(state);

                doWindow(network, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, budgetId, minReplyPad);
            }
        }

        private void doEncodeRequestIfNecessary(
            long traceId,
            long budgetId)
        {
            if (nextRequestId == nextResponseId)
            {
                encoder.accept(traceId, budgetId);
            }
        }

        private void doEncodeDescribeRequest(
            long traceId,
            long budgetId)
        {
            if (KafkaConfiguration.DEBUG)
            {
                System.out.format("[client] %s DESCRIBE\n", delegate.nodeId);
            }

            final MutableDirectBuffer encodeBuffer = writeBuffer;
            final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
            final int encodeLimit = encodeBuffer.capacity();

            int encodeProgress = encodeOffset;

            final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .length(0)
                .apiKey(DESCRIBE_CONFIGS_API_KEY)
                .apiVersion(DESCRIBE_CONFIGS_API_VERSION)
                .correlationId(0)
                .clientId(clientId)
                .build();

            encodeProgress = requestHeader.limit();

            final DescribeConfigsRequestFW describeConfigsRequest =
                describeConfigsRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .resourceCount(1)
                    .build();

            encodeProgress = describeConfigsRequest.limit();

            final ResourceRequestFW resourceRequest = resourceRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .type(RESOURCE_TYPE_BROKER)
                .name(delegate.nodeId)
                .configNamesCount(configs.size())
                .build();

            encodeProgress = resourceRequest.limit();

            for (String config : configs.keySet())
            {
                final String16FW configName = configNameRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .set(config, UTF_8)
                    .build();

                encodeProgress = configName.limit();
            }

            final int requestId = nextRequestId++;
            final int requestSize = encodeProgress - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

            requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                .length(requestSize)
                .apiKey(requestHeader.apiKey())
                .apiVersion(requestHeader.apiVersion())
                .correlationId(requestId)
                .clientId(requestHeader.clientId())
                .build();

            doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress);

            decoder = decodeDescribeResponse;
        }

        private void encodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            final int length = limit - offset;
            final int initialBudget = Math.max(initialMax - (int)(initialSeq - initialAck), 0);
            final int reservedMax = Math.max(Math.min(length + initialPad, initialBudget), initialMin);

            int reserved = reservedMax;

            flush:
            if (reserved > 0)
            {

                boolean claimed = false;

                if (initialDebIndex != NO_DEBITOR_INDEX)
                {
                    reserved = initialDeb.claim(traceId, initialDebIndex, initialId, reservedMax, reservedMax, 0);
                    claimed = reserved > 0;
                }

                if (reserved < initialPad || reserved == initialPad && length > 0)
                {
                    break flush;
                }

                doData(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, budgetId, reserved, buffer, offset, length, EMPTY_EXTENSION);

                initialSeq += reserved;

                assert initialAck <= initialSeq;
            }

            final int flushed = Math.max(reserved - initialPad, 0);
            final int remaining = length - flushed;
            if (remaining > 0)
            {
                if (encodeSlot == NO_SLOT)
                {
                    encodeSlot = encodePool.acquire(initialId);
                }

                if (encodeSlot == NO_SLOT)
                {
                    cleanupNetwork(traceId);
                }
                else
                {
                    final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                    encodeBuffer.putBytes(0, buffer, offset + flushed, remaining);
                    encodeSlotOffset = remaining;
                }
            }
            else
            {
                cleanupEncodeSlotIfNecessary();
            }
        }

        private void decodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int limit)
        {
            KafkaDescribeClientDecoder previous = null;
            int progress = offset;
            while (progress <= limit && previous != decoder)
            {
                previous = decoder;
                progress = decoder.decode(this, traceId, authorization, budgetId, reserved, buffer, offset, progress, limit);
            }

            if (progress < limit)
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = decodePool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    cleanupNetwork(traceId);
                }
                else
                {
                    final MutableDirectBuffer decodeBuffer = decodePool.buffer(decodeSlot);
                    decodeBuffer.putBytes(0, buffer, progress, limit - progress);
                    decodeSlotOffset = limit - progress;
                    decodeSlotReserved = (limit - progress) * reserved / (limit - offset);
                }

                doNetworkWindow(traceId, budgetId, decodeSlotOffset, 0, replyMax);
            }
            else
            {
                cleanupDecodeSlotIfNecessary();

                if (reserved > 0)
                {
                    doNetworkWindow(traceId, budgetId, 0, 0, replyMax);
                }
            }
        }

        @Override
        protected void doDecodeSaslHandshakeResponse(
            long traceId)
        {
            decoder = decodeSaslHandshakeResponse;
        }

        @Override
        protected void doDecodeSaslHandshake(
            long traceId)
        {
            decoder = decodeSaslHandshake;
        }

        @Override
        protected void doDecodeSaslHandshakeMechanisms(
            long traceId)
        {
            decoder = decodeSaslHandshakeMechanisms;
        }

        @Override
        protected void doDecodeSaslHandshakeMechansim(
            long traceId)
        {
            decoder = decodeSaslHandshakeMechanism;
        }

        @Override
        protected void doDecodeSaslAuthenticateResponse(
            long traceId)
        {
            decoder = decodeSaslAuthenticateResponse;
        }

        @Override
        protected void doDecodeSaslAuthenticate(
            long traceId)
        {
            decoder = decodeSaslAuthenticate;
        }

        @Override
        protected void onDecodeSaslHandshakeResponse(
            long traceId,
            long authorization,
            int errorCode)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                encoder = encodeSaslAuthenticateRequest;
                decoder = decodeSaslAuthenticateResponse;
                break;
            default:
                delegate.cleanupStream(traceId, errorCode);
                doNetworkEnd(traceId, authorization);
                break;
            }
        }

        @Override
        protected void onDecodeSaslAuthenticateResponse(
            long traceId,
            long authorization,
            int errorCode)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                encoder = encodeDescribeRequest;
                decoder = decodeDescribeResponse;
                break;
            default:
                delegate.cleanupStream(traceId, errorCode);
                doNetworkEnd(traceId, authorization);
                break;
            }
        }

        @Override
        protected void onDecodeSaslResponse(
            long traceId)
        {
            nextResponseId++;
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void onDecodeDescribeResponse(
            long traceId,
            Map<String, String> newConfigs)
        {
            nextResponseId++;

            int timeoutMin =
                Integer.valueOf(newConfigs.getOrDefault(GROUP_MIN_SESSION_TIMEOUT, groupMinSessionTimeoutDefault)).intValue();
            int timeoutMax =
                Integer.valueOf(newConfigs.getOrDefault(GROUP_MAX_SESSION_TIMEOUT, groupMaxSessionTimeoutDefault)).intValue();
            if (delegate.timeout < timeoutMin)
            {
                delegate.timeout = timeoutMin;
            }
            else if (delegate.timeout > timeoutMax)
            {
                delegate.timeout = timeoutMax;
            }

            delegate.coordinatorClient.doNetworkBeginIfNecessary(traceId, authorization, 0);

            cleanupNetwork(traceId);
        }

        private void cleanupNetwork(
            long traceId)
        {
            doNetworkAbort(traceId);
            doNetworkReset(traceId);
        }

        private void onNetworkError(
            long traceId,
            short errorCode)
        {
            doNetworkAbort(traceId);
            doNetworkReset(traceId);

            delegate.cleanupStream(traceId, errorCode);
        }

        private void cleanupDecodeSlotIfNecessary()
        {
            if (decodeSlot != NO_SLOT)
            {
                decodePool.release(decodeSlot);
                decodeSlot = NO_SLOT;
                decodeSlotOffset = 0;
                decodeSlotReserved = 0;
            }
        }

        private void cleanupEncodeSlotIfNecessary()
        {
            if (encodeSlot != NO_SLOT)
            {
                encodePool.release(encodeSlot);
                encodeSlot = NO_SLOT;
                encodeSlotOffset = 0;
                encodeSlotTraceId = 0;
            }
        }

        private void cleanupBudgetIfNecessary()
        {
            if (initialDebIndex != NO_DEBITOR_INDEX)
            {
                initialDeb.release(initialDebIndex, initialId);
                initialDebIndex = NO_DEBITOR_INDEX;
            }
        }
    }

    private final class CoordinatorClient extends KafkaSaslClient
    {
        private final LongLongConsumer encodeSaslHandshakeRequest = this::doEncodeSaslHandshakeRequest;
        private final LongLongConsumer encodeSaslAuthenticateRequest = this::doEncodeSaslAuthenticateRequest;
        private final LongLongConsumer encodeJoinGroupRequest = this::doEncodeJoinGroupRequest;
        private final LongLongConsumer encodeSyncGroupRequest = this::doEncodeSyncGroupRequest;
        private final LongLongConsumer encodeHeartbeatRequest = this::doEncodeHeartbeatRequest;
        private final LongLongConsumer encodeLeaveGroupRequest = this::doEncodeLeaveGroupRequest;

        private final KafkaGroupStream delegate;
        private final List<MemberProtocol> members;
        private final ArrayDeque<LongLongConsumer> encoders;

        private BudgetDebitor initialDeb;
        private KafkaGroupCoordinatorClientDecoder decoder;
        private OctetsFW assignment = EMPTY_OCTETS;
        private MessageConsumer network;
        private int state;
        private long authorization;
        private short errorCode = ERROR_EXISTS;

        private long initialSeq;
        private long initialAck;
        private int initialMin;
        private int initialMax;
        private int initialPad;
        private long initialBudgetId = NO_BUDGET_ID;
        private long initialDebIndex = NO_CREDITOR_INDEX;

        private long replySeq;
        private long replyAck;
        private int replyMax;

        private int encodeSlot = NO_SLOT;
        private int encodeSlotOffset;
        private long encodeSlotTraceId;

        private int decodeSlot = NO_SLOT;
        private int decodeSlotOffset;
        private int decodeSlotReserved;

        private int nextResponseId;
        private int generationId;
        private long heartbeatRequestId = NO_CANCEL_ID;

        CoordinatorClient(
            long originId,
            long routedId,
            KafkaSaslConfig sasl,
            KafkaGroupStream delegate)
        {
            super(sasl, originId, routedId);

            this.delegate = delegate;
            this.decoder = decodeCoordinatorReject;
            this.members = new ArrayList<>();
            this.encoders = new ArrayDeque<>();
            encoders.add(sasl != null ? encodeSaslHandshakeRequest : encodeJoinGroupRequest);
        }

        private void onNetwork(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onNetworkBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onNetworkData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onNetworkEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onNetworkAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onNetworkReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onNetworkWindow(window);
                break;
            case SignalFW.TYPE_ID:
                final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                onNetworkSignal(signal);
                break;
            default:
                break;
            }
        }

        private void onNetworkBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();

            authorization = begin.authorization();
            state = KafkaState.openingReply(state);

            doNetworkWindow(traceId, 0L, 0, 0, decodePool.slotCapacity());
        }

        private void onNetworkData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long budgetId = data.budgetId();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + data.reserved();
            authorization = data.authorization();

            assert replyAck <= replySeq;

            if (replySeq > replyAck + replyMax)
            {
                onNetworkError(traceId);
            }
            else
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = decodePool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    onNetworkError(traceId);
                }
                else
                {
                    final OctetsFW payload = data.payload();
                    int reserved = data.reserved();
                    int offset = payload.offset();
                    int limit = payload.limit();

                    final MutableDirectBuffer buffer = decodePool.buffer(decodeSlot);
                    buffer.putBytes(decodeSlotOffset, payload.buffer(), offset, limit - offset);
                    decodeSlotOffset += limit - offset;
                    decodeSlotReserved += reserved;

                    offset = 0;
                    limit = decodeSlotOffset;
                    reserved = decodeSlotReserved;

                    decodeNetwork(traceId, authorization, budgetId, reserved, buffer, offset, limit);
                }
            }
        }

        private void onNetworkEnd(
            EndFW end)
        {
            final long traceId = end.traceId();

            state = KafkaState.closedReply(state);

            cleanupDecodeSlotIfNecessary();

            if (!delegate.isStreamReplyOpen())
            {
                //onError(traceId);
            }
        }

        private void onNetworkAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            state = KafkaState.closedReply(state);

            onNetworkError(traceId);
        }

        private void onNetworkReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedInitial(state);

            onNetworkError(traceId);
        }

        private void onNetworkWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int minimum = window.minimum();
            final int maximum = window.maximum();
            final long traceId = window.traceId();
            final long budgetId = window.budgetId();
            final int padding = window.padding();

            assert acknowledge <= sequence;
            assert sequence <= initialSeq;
            assert acknowledge >= initialAck;
            assert maximum + acknowledge >= initialMax + initialAck;

            this.initialAck = acknowledge;
            this.initialMax = maximum;
            this.initialPad = padding;
            this.initialMin = minimum;
            this.initialBudgetId = budgetId;

            assert initialAck <= initialSeq;

            this.authorization = window.authorization();

            state = KafkaState.openedInitial(state);

            if (initialBudgetId != NO_BUDGET_ID && initialDebIndex == NO_DEBITOR_INDEX)
            {
                initialDeb = supplyDebitor.apply(initialBudgetId);
                initialDebIndex = initialDeb.acquire(initialBudgetId, initialId, this::doNetworkDataIfNecessary);
                assert initialDebIndex != NO_DEBITOR_INDEX;
            }

            doNetworkDataIfNecessary(budgetId);

            doEncodeRequestIfNecessary(traceId, budgetId);
        }

        private void doNetworkDataIfNecessary(long budgetId)
        {
            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer buffer = encodePool.buffer(encodeSlot);
                final int limit = encodeSlotOffset;

                encodeNetwork(encodeSlotTraceId, authorization, budgetId, buffer, 0, limit);
            }
        }

        private void onNetworkSignal(
            SignalFW signal)
        {
            final long traceId = signal.traceId();
            final int signalId = signal.signalId();
            final OctetsFW payload = signal.payload();

            switch (signalId)
            {
            case SIGNAL_NEXT_REQUEST:
                doEncodeRequestIfNecessary(traceId, initialBudgetId);
                break;
            case SIGNAL_SYNC_GROUP_REQUEST:
                assignment = payload;
                doEncodeRequestIfNecessary(traceId, initialBudgetId);

                if (decoder != decodeSyncGroupResponse)
                {
                    final DirectBuffer buffer = payload.value();
                    final int offset = 0;
                    final int sizeof = payload.sizeof();

                    signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_SYNC_GROUP_REQUEST, 0,
                        buffer, offset, sizeof);
                }
                break;
            case SIGNAL_HEARTBEAT_REQUEST:
                encoders.add(encodeHeartbeatRequest);
                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
                break;
            }
        }

        private void doNetworkBeginIfNecessary(
            long traceId,
            long authorization,
            long affinity)
        {
            if (KafkaState.closed(state))
            {
                initialSeq = 0;
                initialAck = 0;
                replyAck = 0;
                replySeq = 0;
                state = 0;
                encoders.clear();
                encoders.add(sasl != null ? encodeSaslHandshakeRequest : encodeJoinGroupRequest);
                nextRequestId = 0;
                nextResponseId = 0;
            }

            if (!KafkaState.initialOpening(state))
            {
                doNetworkBegin(traceId, authorization, affinity);
            }
        }

        private void doNetworkBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);

            state = KafkaState.openingInitial(state);

            Consumer<OctetsFW.Builder> extension =  e -> e.set((b, o, l) -> proxyBeginExRW.wrap(b, o, l)
                .typeId(proxyTypeId)
                .address(a -> a.inet(i -> i.protocol(p -> p.set(STREAM))
                    .source("0.0.0.0")
                    .destination(delegate.host)
                    .sourcePort(0)
                    .destinationPort(delegate.port)))
                .infos(i -> i.item(ii -> ii.authority(delegate.host)))
                .build()
                .sizeof());

            network = newStream(this::onNetwork, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, extension);
        }

        @Override
        protected void doNetworkData(
            long traceId,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                encodeBuffer.putBytes(encodeSlotOffset, buffer, offset, limit - offset);
                encodeSlotOffset += limit - offset;
                encodeSlotTraceId = traceId;

                buffer = encodeBuffer;
                offset = 0;
                limit = encodeSlotOffset;
            }

            encodeNetwork(traceId, authorization, budgetId, buffer, offset, limit);
        }

        private void doNetworkEnd(
            long traceId,
            long authorization)
        {
            cancelHeartbeat();

            if (KafkaState.initialOpening(state) && !KafkaState.initialClosed(state))
            {
                state = KafkaState.closedInitial(state);

                doEnd(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, EMPTY_EXTENSION);
            }

            cleanupEncodeSlotIfNecessary();
            cleanupBudgetIfNecessary();

        }

        private void doNetworkAbort(
            long traceId)
        {
            cancelHeartbeat();

            if (KafkaState.initialOpening(state) &&
                !KafkaState.initialClosed(state))
            {
                doAbort(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, EMPTY_EXTENSION);
                state = KafkaState.closedInitial(state);
            }

            cleanupEncodeSlotIfNecessary();
            cleanupBudgetIfNecessary();
        }

        private void doNetworkReset(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doReset(network, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, EMPTY_OCTETS);
                state = KafkaState.closedReply(state);
            }

            cleanupDecodeSlotIfNecessary();
        }

        private void doNetworkWindow(
            long traceId,
            long budgetId,
            int minReplyNoAck,
            int minReplyPad,
            int minReplyMax)
        {
            final long newReplyAck = Math.max(replySeq - minReplyNoAck, replyAck);

            if (newReplyAck > replyAck || minReplyMax > replyMax || !KafkaState.replyOpened(state))
            {
                replyAck = newReplyAck;
                assert replyAck <= replySeq;

                replyMax = minReplyMax;

                state = KafkaState.openedReply(state);

                doWindow(network, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, budgetId, minReplyPad);
            }
        }

        private void doEncodeRequestIfNecessary(
            long traceId,
            long budgetId)
        {
            if (nextRequestId == nextResponseId && !encoders.isEmpty())
            {
                LongLongConsumer encoder = encoders.remove();
                encoder.accept(traceId, budgetId);
            }
        }

        private void doEncodeJoinGroupRequest(
            long traceId,
            long budgetId)
        {
            final MutableDirectBuffer encodeBuffer = writeBuffer;
            final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
            final int encodeLimit = encodeBuffer.capacity();

            int encodeProgress = encodeOffset;

            final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .length(0)
                .apiKey(JOIN_GROUP_API_KEY)
                .apiVersion(JOIN_GROUP_VERSION)
                .correlationId(0)
                .clientId(clientId)
                .build();

            encodeProgress = requestHeader.limit();

            final String memberId = delegate.groupMembership.memberIds.getOrDefault(delegate.groupId, UNKNOWN_MEMBER_ID);

            final JoinGroupRequestFW joinGroupRequest =
                joinGroupRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .groupId(delegate.groupId)
                    .sessionTimeoutMillis(delegate.timeout)
                    .rebalanceTimeoutMillis((int) rebalanceTimeout.toMillis())
                    .memberId(memberId)
                    .groupInstanceId(delegate.groupMembership.instanceId)
                    .protocolType("consumer")
                    .protocolCount(1)
                    .build();

            encodeProgress = joinGroupRequest.limit();

            final int metadataLimit = delegate.topicMetadataLimit > 0 ? doGenerateSubscriptionMetadata() :
                doGenerateEmptySubscriptionMetadata();

            final ProtocolMetadataFW protocolMetadata =
                protocolMetadataRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .name(delegate.protocol)
                    .metadata(extBuffer, 0, metadataLimit)
                    .build();

            encodeProgress = protocolMetadata.limit();

            final int requestId = nextRequestId++;
            final int requestSize = encodeProgress - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

            requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                .length(requestSize)
                .apiKey(requestHeader.apiKey())
                .apiVersion(requestHeader.apiVersion())
                .correlationId(requestId)
                .clientId(requestHeader.clientId())
                .build();

            doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress);

            decoder = decodeJoinGroupResponse;

            delegate.doStreamBeginIfNecessary(traceId, authorization);
        }

        private int doGenerateSubscriptionMetadata()
        {
            final MutableDirectBuffer encodeBuffer = extBuffer;
            final int encodeOffset = 0;
            final int encodeLimit = encodeBuffer.capacity();

            final MutableInteger encodeProgress = new MutableInteger(encodeOffset);

            KafkaGroupMemberMetadataFW memberMetadata = kafkaMemberMetadataRO
                .wrap(delegate.metadataBuffer, 0, delegate.topicMetadataLimit);

            ConsumerSubscriptionMetadataFW metadata = groupSubscriptionMetadataRW
                .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                .version(METADATA_LOWEST_VERSION)
                .metadataTopicCount(memberMetadata.topics().fieldCount())
                .build();

            encodeProgress.set(metadata.limit());

            memberMetadata.topics().forEach(t ->
            {
                ConsumerMetadataTopicFW metadataTopic = metadataTopicRW
                    .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                    .name(t.topic())
                    .build();
                encodeProgress.set(metadataTopic.limit());
            });

            memberMetadata.topics().forEach(t ->
            {
                final ConsumerSubscriptionUserdataFW userdata = subscriptionUserdataRW
                    .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                    .userdata(delegate.metadataBuffer, 0, delegate.topicMetadataLimit)
                    .ownedPartitions(0)
                    .build();

                encodeProgress.set(userdata.limit());
            });

            return encodeProgress.get();
        }

        private int doGenerateEmptySubscriptionMetadata()
        {
            final MutableDirectBuffer encodeBuffer = extBuffer;
            final int encodeOffset = 0;
            final int encodeLimit = encodeBuffer.capacity();

            final MutableInteger encodeProgress = new MutableInteger(encodeOffset);

            ConsumerSubscriptionMetadataFW metadata = groupSubscriptionMetadataRW
                .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                .version(METADATA_LOWEST_VERSION)
                .metadataTopicCount(0)
                .build();

            encodeProgress.set(metadata.limit());

            final ConsumerSubscriptionUserdataFW userdata = subscriptionUserdataRW
                .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                .userdata(delegate.metadataBuffer, 0, delegate.topicMetadataLimit)
                .ownedPartitions(0)
                .build();

            encodeProgress.set(userdata.limit());

            return encodeProgress.get();
        }

        private int doGenerateAssignmentMetadata(
            Array32FW<TopicAssignmentFW> topicPartitions,
            ConsumerAssignmentTopicsUserdataFW.Builder assignmentTopicsUserdataRW,
            int progressOffset)
        {
            final MutableDirectBuffer encodeBuffer = extBuffer;
            final int encodeOffset = progressOffset;
            final int encodeLimit = encodeBuffer.capacity();

            final MutableInteger encodeProgress = new MutableInteger(encodeOffset);

            ConsumerAssignmentMetadataFW metadata = assignmentMetadataRW
                .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                .version(METADATA_LOWEST_VERSION)
                .metadataTopicCount(topicPartitions.fieldCount())
                .build();

            encodeProgress.set(metadata.limit());

            topicPartitions.forEach(t ->
            {
                final Array32FW<TopicPartitionFW> partitions = t.partitions();

                ConsumerTopicPartitionFW topicPartition = topicPartitionRW
                    .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                    .topic(t.topic())
                    .partitionCount(partitions.fieldCount())
                    .build();
                encodeProgress.set(topicPartition.limit());

                partitions.forEach(p ->
                {
                    ConsumerPartitionFW partition = partitionRW.wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                        .partitionId(p.partitionId())
                        .build();
                    encodeProgress.set(partition.limit());
                });

                Array32FW<ConsumerAssignmentFW> assignmentUserdata = t.userdata();
                assignmentTopicsUserdataRW.topicsUserdataItem(u -> u
                    .topic(t.topic())
                    .userdata(assignmentUserdata.buffer(), assignmentUserdata.offset(), assignmentUserdata.sizeof()));
            });

            ConsumerAssignmentTopicsUserdataFW topicUserdata = assignmentTopicsUserdataRW.build();
            final ConsumerAssignmentUserdataFW userdata = assignmentUserdataRW
                .wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                .userdata(topicUserdata.buffer(), topicUserdata.offset(), topicUserdata.sizeof())
                .build();

            encodeProgress.set(userdata.limit());

            return encodeProgress.get();
        }

        private void doEncodeSyncGroupRequest(
            long traceId,
            long budgetId)
        {
            final MutableDirectBuffer encodeBuffer = writeBuffer;
            final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
            final int encodeLimit = encodeBuffer.capacity();

            MutableInteger encodeProgress = new MutableInteger(encodeOffset);

            final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                .length(0)
                .apiKey(SYNC_GROUP_API_KEY)
                .apiVersion(SYNC_GROUP_VERSION)
                .correlationId(0)
                .clientId(clientId)
                .build();

            encodeProgress.set(requestHeader.limit());

            final String memberId = delegate.groupMembership.memberIds.get(delegate.groupId);

            final SyncGroupRequestFW syncGroupRequest =
                syncGroupRequestRW.wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                    .groupId(delegate.groupId)
                    .generatedId(generationId)
                    .memberId(memberId)
                    .groupInstanceId(delegate.groupMembership.instanceId)
                    .assignmentCount(members.size())
                    .build();

            encodeProgress.set(syncGroupRequest.limit());

            if (assignment.sizeof() > 0)
            {
                Array32FW<MemberAssignmentFW> assignments = memberAssignmentRO
                    .wrap(assignment.buffer(), assignment.offset(), assignment.limit());

                MutableInteger progressOffset = new MutableInteger();
                assignments.forEach(a ->
                {
                    Array32FW<TopicAssignmentFW> topicPartitions = a.assignments();

                    assignmentTopicsUserdataRW.wrap(userdataBuffer, assignmentTopicsUserdataRW.limit(),
                        userdataBuffer.capacity());

                    int newProgressOffset = doGenerateAssignmentMetadata(topicPartitions, assignmentTopicsUserdataRW,
                        progressOffset.get());
                    final AssignmentFW memberAssignment =
                        assignmentRW.wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                            .memberId(a.memberId())
                            .value(extBuffer, progressOffset.get(), newProgressOffset)
                            .build();

                    encodeProgress.set(memberAssignment.limit());
                    progressOffset.set(newProgressOffset);
                });
            }
            else
            {
                members.forEach(m ->
                {
                    final AssignmentFW groupAssignment =
                        assignmentRW.wrap(encodeBuffer, encodeProgress.get(), encodeLimit)
                            .memberId(m.memberId)
                            .value(EMPTY_OCTETS)
                            .build();

                    encodeProgress.set(groupAssignment.limit());
                });
            }


            final int requestId = nextRequestId++;
            final int requestSize = encodeProgress.get() - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

            requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                .length(requestSize)
                .apiKey(requestHeader.apiKey())
                .apiVersion(requestHeader.apiVersion())
                .correlationId(requestId)
                .clientId(requestHeader.clientId())
                .build();

            doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress.get());

            decoder = decodeSyncGroupResponse;
        }

        private void doEncodeHeartbeatRequest(
            long traceId,
            long budgetId)
        {
            final MutableDirectBuffer encodeBuffer = writeBuffer;
            final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
            final int encodeLimit = encodeBuffer.capacity();

            int encodeProgress = encodeOffset;

            final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .length(0)
                .apiKey(HEARTBEAT_API_KEY)
                .apiVersion(HEARTBEAT_VERSION)
                .correlationId(0)
                .clientId(clientId)
                .build();

            encodeProgress = requestHeader.limit();

            final String memberId = delegate.groupMembership.memberIds.get(delegate.groupId);

            final HeartbeatRequestFW heartbeatRequest =
                heartbeatRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .groupId(delegate.groupId)
                    .generatedId(generationId)
                    .memberId(memberId)
                    .groupInstanceId(delegate.groupMembership.instanceId)
                    .build();

            encodeProgress = heartbeatRequest.limit();

            final int requestId = nextRequestId++;
            final int requestSize = encodeProgress - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

            requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                .length(requestSize)
                .apiKey(requestHeader.apiKey())
                .apiVersion(requestHeader.apiVersion())
                .correlationId(requestId)
                .clientId(requestHeader.clientId())
                .build();

            doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress);

            decoder = decodeHeartbeatResponse;
        }

        private void doEncodeLeaveGroupRequest(
            long traceId,
            long budgetId)
        {
            final MutableDirectBuffer encodeBuffer = writeBuffer;
            final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
            final int encodeLimit = encodeBuffer.capacity();

            int encodeProgress = encodeOffset;

            final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .length(0)
                .apiKey(LEAVE_GROUP_API_KEY)
                .apiVersion(LEAVE_GROUP_VERSION)
                .correlationId(0)
                .clientId(clientId)
                .build();

            encodeProgress = requestHeader.limit();

            final LeaveGroupRequestFW leaveGroupRequest =
                leaveGroupRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                    .groupId(delegate.groupId)
                    .memberCount(1)
                    .build();

            encodeProgress = leaveGroupRequest.limit();

            final String memberId = delegate.groupMembership.memberIds.get(delegate.groupId);

            final LeaveMemberRequestFW leaveMember = leaveMemberRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                .memberId(memberId)
                .groupInstanceId(delegate.groupMembership.instanceId)
                .build();

            encodeProgress = leaveMember.limit();

            final int requestId = nextRequestId++;
            final int requestSize = encodeProgress - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

            requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                .length(requestSize)
                .apiKey(requestHeader.apiKey())
                .apiVersion(requestHeader.apiVersion())
                .correlationId(requestId)
                .clientId(requestHeader.clientId())
                .build();

            doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress);

            decoder = decodeLeaveGroupResponse;
        }

        private void doSyncGroupRequest(
            long traceId,
            long budgetId,
            OctetsFW assignment)
        {
            final DirectBuffer buffer = assignment.value();
            final int offset = 0;
            final int sizeof = assignment.sizeof();

            encoders.add(encodeSyncGroupRequest);
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_SYNC_GROUP_REQUEST, 0,
                buffer, offset, sizeof);
        }

        private void doJoinGroupRequest(
            long traceId)
        {
            cancelHeartbeat();

            if (KafkaState.initialOpening(state) &&
                decoder != decodeJoinGroupResponse)
            {
                encoders.add(encodeJoinGroupRequest);
                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
            }
            else if (delegate.host != null)
            {
                delegate.doStreamBeginIfNecessary(traceId, authorization);
            }
        }

        private void doHeartbeatRequest(
            long traceId)
        {
            final String memberId = delegate.groupMembership.memberIds.getOrDefault(delegate.groupId, UNKNOWN_MEMBER_ID);

            if (KafkaState.initialOpened(state) &&
                !memberId.equals(UNKNOWN_MEMBER_ID))
            {
                cancelHeartbeat();

                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_HEARTBEAT_REQUEST, 0);
            }
        }

        private void doLeaveGroupRequest(
            long traceId)
        {
            cancelHeartbeat();

            final String memberId = delegate.groupMembership.memberIds.getOrDefault(delegate.groupId, UNKNOWN_MEMBER_ID);

            if (KafkaState.initialOpened(state) &&
                !memberId.equals(UNKNOWN_MEMBER_ID))
            {
                encoders.add(encodeLeaveGroupRequest);
                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
            }
        }

        private void encodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            final int length = limit - offset;
            final int initialBudget = Math.max(initialMax - (int)(initialSeq - initialAck), 0);
            final int reservedMax = Math.max(Math.min(length + initialPad, initialBudget), initialMin);

            int reserved = reservedMax;

            flush:
            if (reserved > 0)
            {

                boolean claimed = false;

                if (initialDebIndex != NO_DEBITOR_INDEX)
                {
                    reserved = initialDeb.claim(traceId, initialDebIndex, initialId, reserved, reserved, 0);
                    claimed = reserved > 0;
                }

                if (reserved < initialPad || reserved == initialPad && length > 0)
                {
                    break flush;
                }

                doData(network, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, budgetId, reserved, buffer, offset, length, EMPTY_EXTENSION);

                initialSeq += reserved;

                assert initialAck <= initialSeq;
            }

            final int flushed = Math.max(reserved - initialPad, 0);
            final int remaining = length - flushed;
            if (remaining > 0)
            {
                if (encodeSlot == NO_SLOT)
                {
                    encodeSlot = encodePool.acquire(initialId);
                }

                if (encodeSlot == NO_SLOT)
                {
                    onNetworkError(traceId);
                }
                else
                {
                    final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                    encodeBuffer.putBytes(0, buffer, offset + flushed, remaining);
                    encodeSlotOffset = remaining;
                }
            }
            else
            {
                cleanupEncodeSlotIfNecessary();
            }
        }

        private void decodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int limit)
        {
            KafkaGroupCoordinatorClientDecoder previous = null;
            int progress = offset;
            while (progress <= limit && previous != decoder)
            {
                previous = decoder;
                progress = decoder.decode(this, traceId, authorization, budgetId, reserved, buffer, offset, progress, limit);
            }

            if (progress < limit)
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = decodePool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    onNetworkError(traceId);
                }
                else
                {
                    final MutableDirectBuffer decodeBuffer = decodePool.buffer(decodeSlot);
                    decodeBuffer.putBytes(0, buffer, progress, limit - progress);
                    decodeSlotOffset = limit - progress;
                    decodeSlotReserved = (limit - progress) * reserved / (limit - offset);
                }

                doNetworkWindow(traceId, budgetId, decodeSlotOffset, 0, replyMax);
            }
            else
            {
                cleanupDecodeSlotIfNecessary();

                if (reserved > 0)
                {
                    doNetworkWindow(traceId, budgetId, 0, 0, replyMax);
                }
            }
        }

        @Override
        protected void doDecodeSaslHandshakeResponse(
            long traceId)
        {
            decoder = decodeCoordinatorSaslHandshakeResponse;
        }

        @Override
        protected void doDecodeSaslHandshake(
            long traceId)
        {
            decoder = decodeCoordinatorSaslHandshake;
        }

        @Override
        protected void doDecodeSaslHandshakeMechanisms(
            long traceId)
        {
            decoder = decodeCoordinatorSaslHandshakeMechanisms;
        }

        @Override
        protected void doDecodeSaslHandshakeMechansim(
            long traceId)
        {
            decoder = decodeCoordinatorSaslHandshakeMechanism;
        }

        @Override
        protected void doDecodeSaslAuthenticateResponse(
            long traceId)
        {
            decoder = decodeCoordinatorSaslAuthenticateResponse;
        }

        @Override
        protected void doDecodeSaslAuthenticate(
            long traceId)
        {
            decoder = decodeCoordinatorSaslAuthenticate;
        }

        @Override
        protected void onDecodeSaslHandshakeResponse(
            long traceId,
            long authorization,
            int errorCode)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                encoders.add(encodeSaslAuthenticateRequest);
                decoder = decodeCoordinatorSaslAuthenticateResponse;
                break;
            default:
                delegate.cleanupStream(traceId, errorCode);
                doNetworkEnd(traceId, authorization);
                break;
            }
        }

        @Override
        protected void onDecodeSaslAuthenticateResponse(
            long traceId,
            long authorization,
            int errorCode)
        {
            switch (errorCode)
            {
            case ERROR_NONE:
                encoders.add(encodeJoinGroupRequest);
                decoder = decodeJoinGroupResponse;
                break;
            default:
                delegate.cleanupStream(traceId, errorCode);
                doNetworkEnd(traceId, authorization);
                break;
            }
        }

        @Override
        protected void onDecodeSaslResponse(
            long traceId)
        {
            nextResponseId++;
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void onNotCoordinatorError(
            long traceId,
            long authorization)
        {
            nextResponseId++;

            cleanupNetwork(traceId, authorization);

            delegate.onNotCoordinatorError(traceId, authorization);
        }

        private void onJoinGroupMemberIdError(
            long traceId,
            long authorization,
            String memberId)
        {
            nextResponseId++;

            delegate.groupMembership.memberIds.put(delegate.groupId, memberId);

            encoders.add(encodeJoinGroupRequest);
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void onJoinGroupResponse(
            long traceId,
            long authorization,
            String leaderId,
            String memberId)
        {
            nextResponseId++;

            delegate.groupMembership.memberIds.put(delegate.groupId, memberId);

            delegate.doStreamFlush(traceId, authorization,
                ex -> ex.set((b, o, l) -> kafkaFlushExRW.wrap(b, o, l)
                    .typeId(kafkaTypeId)
                    .group(g -> g
                        .generationId(generationId)
                        .leaderId(leaderId)
                        .memberId(memberId)
                        .members(gm -> members.forEach(m ->
                        {
                            OctetsFW metadata = m.metadata;
                            DirectBuffer buffer = metadata.value();
                            final int limit = metadata.sizeof();

                            int progress = 0;

                            ConsumerSubscriptionMetadataFW newGroupMetadata = subscriptionMetadataRO
                                .wrap(buffer, 0, metadata.sizeof());
                            progress = newGroupMetadata.limit();

                            for (int i = 0; i < newGroupMetadata.metadataTopicCount(); i++)
                            {
                                ConsumerMetadataTopicFW topic = metadataTopicRO.wrap(buffer, progress, limit);
                                progress = topic.limit();
                            }

                            ConsumerSubscriptionUserdataFW userdata = subscriptionUserdataRO.wrap(buffer, progress, limit);

                            gm.item(i ->
                            {
                                KafkaGroupMemberFW.Builder builder = i.id(m.memberId);
                                OctetsFW newUserdata = userdata.userdata();
                                if (newUserdata.sizeof() > 0)
                                {
                                    builder.metadataLen(newUserdata.sizeof()).metadata(newUserdata);
                                }
                            });
                        })))
                    .build()
                    .sizeof()));

            if (!encoders.isEmpty())
            {
                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
            }
        }

        private void onSyncGroupRebalance(
            long traceId,
            long authorization)
        {
            nextResponseId++;

            encoders.add(encodeJoinGroupRequest);
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void onSyncGroupResponse(
            long traceId,
            long authorization,
            OctetsFW newAssignment)
        {
            nextResponseId++;

            if (newAssignment.sizeof() > 0)
            {
                Array32FW.Builder<TopicAssignmentFW.Builder, TopicAssignmentFW> topicAssignmentBuilder =
                    topicPartitionsRW.wrap(extBuffer, 0, extBuffer.capacity());

                final DirectBuffer buffer = newAssignment.value();
                final int limit = newAssignment.sizeof();

                MutableInteger progress = new MutableInteger();
                MutableInteger userdataProgress = new MutableInteger();

                final ConsumerAssignmentMetadataFW assignment = assignmentMetadataRO.wrap(buffer, progress.get(), limit);
                progress.set(assignment.limit());
                userdataProgress.set(assignment.limit());

                for (int i = 0; i < assignment.metadataTopicCount(); i++)
                {
                    final ConsumerTopicPartitionFW topicPartition = topicPartitionRO
                        .wrap(buffer, userdataProgress.get(), limit);

                    userdataProgress.set(topicPartition.limit());

                    int partitionCount = topicPartition.partitionCount();
                    for (int t = 0; t < partitionCount; t++)
                    {
                        ConsumerPartitionFW partition = partitionRO.wrap(buffer, userdataProgress.get(), limit);
                        userdataProgress.set(partition.limit());
                    }
                }

                final ConsumerAssignmentUserdataFW memberUserdata =
                    assignmentUserdataRO.wrap(buffer, userdataProgress.get(), limit);

                final OctetsFW memberUserdataValue = memberUserdata.userdata();
                ConsumerAssignmentTopicsUserdataFW assignmentUserdata =
                    assignmentTopicsUserdataRO.wrap(memberUserdataValue.value(), 0, limit);

                for (int i = 0; i < assignment.metadataTopicCount(); i++)
                {
                    ConsumerTopicPartitionFW topicPartition = topicPartitionRO
                        .wrap(buffer, progress.get(), limit);

                    progress.set(topicPartition.limit());

                    topicAssignmentBuilder.item(ta ->
                    {
                        final String16FW topic = topicPartition.topic();
                        ta.topic(topic);
                        int partitionCount = topicPartition.partitionCount();
                        for (int t = 0; t < partitionCount; t++)
                        {
                            ConsumerPartitionFW partition = partitionRO.wrap(buffer, progress.get(), limit);
                            progress.set(partition.limit());
                            ta.partitionsItem(p -> p.partitionId(partition.partitionId()));
                        }

                        ConsumerAssignmentTopicUserdataFW consumerAssignmentTopicUserdata =
                            assignmentUserdata.topicsUserdata().matchFirst(tu -> tu.topic().equals(topic));

                        final OctetsFW userdata = consumerAssignmentTopicUserdata.userdata();
                        assignmentConsumersRO.wrap(userdata.value(), 0, userdata.sizeof());
                        ta.userdata(assignmentConsumersRO);
                    });
                }

                Array32FW<TopicAssignmentFW> topicAssignment = topicAssignmentBuilder.build();

                delegate.doStreamData(traceId, authorization, topicAssignment.buffer(), topicAssignment.offset(),
                    topicAssignment.sizeof());
            }
            else
            {
                delegate.doStreamData(traceId, authorization, EMPTY_OCTETS.buffer(), EMPTY_OCTETS.offset(),
                    EMPTY_OCTETS.sizeof());
            }

            cancelHeartbeat();

            heartbeatRequestId = signaler.signalAt(currentTimeMillis() + delegate.timeout / 2,
                originId, routedId, initialId, traceId, SIGNAL_HEARTBEAT_REQUEST, 0);

            if (!encoders.isEmpty())
            {
                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
            }
        }

        private void onHeartbeatResponse(
            long traceId,
            long authorization)
        {
            nextResponseId++;

            cancelHeartbeat();

            heartbeatRequestId = signaler.signalAt(currentTimeMillis() + delegate.timeout / 2,
                originId, routedId, initialId, traceId, SIGNAL_HEARTBEAT_REQUEST, 0);

            if (!encoders.isEmpty())
            {
                signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
            }
        }

        private void onLeaveGroupResponse(
            long traceId,
            long authorization)
        {
            doNetworkEnd(traceId, authorization);
            doNetworkReset(traceId);

            delegate.onLeaveGroup(traceId);
        }

        private void onRebalanceError(
            long traceId,
            long authorization)
        {
            nextResponseId++;

            encoders.add(encodeJoinGroupRequest);
            signaler.signalNow(originId, routedId, initialId, traceId, SIGNAL_NEXT_REQUEST, 0);
        }

        private void cleanupNetwork(
            long traceId,
            long authorization)
        {
            doNetworkEnd(traceId, authorization);
            doNetworkReset(traceId);
        }

        private void onNetworkError(
            long traceId)
        {
            doNetworkAbort(traceId);
            doNetworkReset(traceId);

            delegate.cleanupStream(traceId, errorCode);
        }

        private void cancelHeartbeat()
        {
            if (heartbeatRequestId != NO_CANCEL_ID)
            {
                signaler.cancel(heartbeatRequestId);
                heartbeatRequestId = NO_CANCEL_ID;
            }
        }

        private void cleanupDecodeSlotIfNecessary()
        {
            if (decodeSlot != NO_SLOT)
            {
                decodePool.release(decodeSlot);
                decodeSlot = NO_SLOT;
                decodeSlotOffset = 0;
                decodeSlotReserved = 0;
            }
        }

        private void cleanupEncodeSlotIfNecessary()
        {
            if (encodeSlot != NO_SLOT)
            {
                encodePool.release(encodeSlot);
                encodeSlot = NO_SLOT;
                encodeSlotOffset = 0;
                encodeSlotTraceId = 0;
            }
        }

        private void cleanupBudgetIfNecessary()
        {
            if (initialDebIndex != NO_DEBITOR_INDEX)
            {
                initialDeb.release(initialDebIndex, initialId);
                initialDebIndex = NO_DEBITOR_INDEX;
            }
        }
    }

    private final class GroupMembership
    {
        public final String instanceId;
        public final Map<String, String> memberIds;

        GroupMembership(
            String instanceId)
        {
            this.instanceId = instanceId;
            this.memberIds = new Object2ObjectHashMap<>();
        }
    }

    private final class MemberProtocol
    {
        private final String memberId;
        private final OctetsFW metadata;

        MemberProtocol(
            String memberId,
            OctetsFW metadata)
        {

            this.memberId = memberId;
            this.metadata = metadata;
        }
    }
}
