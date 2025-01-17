/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.gateway;

import org.opensearch.OpenSearchException;
import org.opensearch.action.ActionType;
import org.opensearch.action.FailedNodeException;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.nodes.BaseNodeResponse;
import org.opensearch.action.support.nodes.BaseNodesRequest;
import org.opensearch.action.support.nodes.BaseNodesResponse;
import org.opensearch.action.support.nodes.TransportNodesAction;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.store.ShardAttributes;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.opensearch.gateway.TransportNodesGatewayStartedShardHelper.getShardInfoOnLocalNode;

/**
 * This transport action is used to fetch batch of unassigned shard version from each node during primary allocation in {@link GatewayAllocator}.
 * We use this to find out which node holds the latest shard version and which of them used to be a primary in order to allocate
 * shards after node or cluster restarts.
 *
 * @opensearch.internal
 */
public class TransportNodesListGatewayStartedShardsBatch extends TransportNodesAction<
    TransportNodesListGatewayStartedShardsBatch.Request,
    TransportNodesListGatewayStartedShardsBatch.NodesGatewayStartedShardsBatch,
    TransportNodesListGatewayStartedShardsBatch.NodeRequest,
    TransportNodesListGatewayStartedShardsBatch.NodeGatewayStartedShardsBatch>
    implements
        AsyncShardFetch.Lister<
            TransportNodesListGatewayStartedShardsBatch.NodesGatewayStartedShardsBatch,
            TransportNodesListGatewayStartedShardsBatch.NodeGatewayStartedShardsBatch> {

    public static final String ACTION_NAME = "internal:gateway/local/started_shards_batch";
    public static final ActionType<NodesGatewayStartedShardsBatch> TYPE = new ActionType<>(
        ACTION_NAME,
        NodesGatewayStartedShardsBatch::new
    );

    private final Settings settings;
    private final NodeEnvironment nodeEnv;
    private final IndicesService indicesService;
    private final NamedXContentRegistry namedXContentRegistry;

    @Inject
    public TransportNodesListGatewayStartedShardsBatch(
        Settings settings,
        ThreadPool threadPool,
        ClusterService clusterService,
        TransportService transportService,
        ActionFilters actionFilters,
        NodeEnvironment env,
        IndicesService indicesService,
        NamedXContentRegistry namedXContentRegistry
    ) {
        super(
            ACTION_NAME,
            threadPool,
            clusterService,
            transportService,
            actionFilters,
            Request::new,
            NodeRequest::new,
            ThreadPool.Names.FETCH_SHARD_STARTED,
            NodeGatewayStartedShardsBatch.class
        );
        this.settings = settings;
        this.nodeEnv = env;
        this.indicesService = indicesService;
        this.namedXContentRegistry = namedXContentRegistry;
    }

    @Override
    public void list(
        Map<ShardId, ShardAttributes> shardAttributesMap,
        DiscoveryNode[] nodes,
        ActionListener<NodesGatewayStartedShardsBatch> listener
    ) {
        execute(new Request(nodes, shardAttributesMap), listener);
    }

    @Override
    protected NodeRequest newNodeRequest(Request request) {
        return new NodeRequest(request);
    }

    @Override
    protected NodeGatewayStartedShardsBatch newNodeResponse(StreamInput in) throws IOException {
        return new NodeGatewayStartedShardsBatch(in);
    }

    @Override
    protected NodesGatewayStartedShardsBatch newResponse(
        Request request,
        List<NodeGatewayStartedShardsBatch> responses,
        List<FailedNodeException> failures
    ) {
        return new NodesGatewayStartedShardsBatch(clusterService.getClusterName(), responses, failures);
    }

    /**
     * This function is similar to nodeOperation method of {@link TransportNodesListGatewayStartedShards} we loop over
     * the shards here and populate the data about the shards held by the local node.
     *
     * @param request Request containing the map shardIdsWithCustomDataPath.
     * @return NodeGatewayStartedShardsBatch contains the data about the primary shards held by the local node
     */
    @Override
    protected NodeGatewayStartedShardsBatch nodeOperation(NodeRequest request) {
        Map<ShardId, NodeGatewayStartedShard> shardsOnNode = new HashMap<>();
        for (ShardAttributes shardAttr : request.shardAttributes.values()) {
            final ShardId shardId = shardAttr.getShardId();
            try {
                shardsOnNode.put(
                    shardId,
                    getShardInfoOnLocalNode(
                        logger,
                        shardId,
                        namedXContentRegistry,
                        nodeEnv,
                        indicesService,
                        shardAttr.getCustomDataPath(),
                        settings,
                        clusterService
                    )
                );
            } catch (Exception e) {
                shardsOnNode.put(
                    shardId,
                    new NodeGatewayStartedShard(null, false, null, new OpenSearchException("failed to load started shards", e))
                );
            }
        }
        return new NodeGatewayStartedShardsBatch(clusterService.localNode(), shardsOnNode);
    }

    /**
     * This is used in constructing the request for making the transport request to set of other node.
     * Refer {@link TransportNodesAction} class start method.
     *
     * @opensearch.internal
     */
    public static class Request extends BaseNodesRequest<Request> {
        private final Map<ShardId, ShardAttributes> shardAttributes;

        public Request(StreamInput in) throws IOException {
            super(in);
            shardAttributes = in.readMap(ShardId::new, ShardAttributes::new);
        }

        public Request(DiscoveryNode[] nodes, Map<ShardId, ShardAttributes> shardAttributes) {
            super(nodes);
            this.shardAttributes = Objects.requireNonNull(shardAttributes);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeMap(shardAttributes, (o, k) -> k.writeTo(o), (o, v) -> v.writeTo(o));
        }

        public Map<ShardId, ShardAttributes> getShardAttributes() {
            return shardAttributes;
        }
    }

    /**
     * Responses received from set of other nodes is clubbed into this class and sent back to the caller
     * of this transport request. Refer {@link TransportNodesAction}
     *
     * @opensearch.internal
     */
    public static class NodesGatewayStartedShardsBatch extends BaseNodesResponse<NodeGatewayStartedShardsBatch> {

        public NodesGatewayStartedShardsBatch(StreamInput in) throws IOException {
            super(in);
        }

        public NodesGatewayStartedShardsBatch(
            ClusterName clusterName,
            List<NodeGatewayStartedShardsBatch> nodes,
            List<FailedNodeException> failures
        ) {
            super(clusterName, nodes, failures);
        }

        @Override
        protected List<NodeGatewayStartedShardsBatch> readNodesFrom(StreamInput in) throws IOException {
            return in.readList(NodeGatewayStartedShardsBatch::new);
        }

        @Override
        protected void writeNodesTo(StreamOutput out, List<NodeGatewayStartedShardsBatch> nodes) throws IOException {
            out.writeList(nodes);
        }
    }

    /**
     * NodeRequest class is for deserializing the  request received by this node from other node for this transport action.
     * This is used in {@link TransportNodesAction}
     *
     * @opensearch.internal
     */
    public static class NodeRequest extends TransportRequest {
        private final Map<ShardId, ShardAttributes> shardAttributes;

        public NodeRequest(StreamInput in) throws IOException {
            super(in);
            shardAttributes = in.readMap(ShardId::new, ShardAttributes::new);
        }

        public NodeRequest(Request request) {
            this.shardAttributes = Objects.requireNonNull(request.getShardAttributes());
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeMap(shardAttributes, (o, k) -> k.writeTo(o), (o, v) -> v.writeTo(o));
        }
    }

    /**
     * This class encapsulates the metadata about a started shard that needs to be persisted or sent between nodes.
     * This is used in {@link NodeGatewayStartedShardsBatch} to construct the response for each node, instead of
     * {@link TransportNodesListGatewayStartedShards.NodeGatewayStartedShards} because we don't need to save an extra
     * {@link DiscoveryNode} object like in {@link TransportNodesListGatewayStartedShards.NodeGatewayStartedShards}
     * which reduces memory footprint of its objects.
     *
     * @opensearch.internal
     */
    public static class NodeGatewayStartedShard {
        private final String allocationId;
        private final boolean primary;
        private final Exception storeException;
        private final ReplicationCheckpoint replicationCheckpoint;

        public NodeGatewayStartedShard(StreamInput in) throws IOException {
            allocationId = in.readOptionalString();
            primary = in.readBoolean();
            if (in.readBoolean()) {
                storeException = in.readException();
            } else {
                storeException = null;
            }
            if (in.readBoolean()) {
                replicationCheckpoint = new ReplicationCheckpoint(in);
            } else {
                replicationCheckpoint = null;
            }
        }

        public NodeGatewayStartedShard(String allocationId, boolean primary, ReplicationCheckpoint replicationCheckpoint) {
            this(allocationId, primary, replicationCheckpoint, null);
        }

        public NodeGatewayStartedShard(
            String allocationId,
            boolean primary,
            ReplicationCheckpoint replicationCheckpoint,
            Exception storeException
        ) {
            this.allocationId = allocationId;
            this.primary = primary;
            this.replicationCheckpoint = replicationCheckpoint;
            this.storeException = storeException;
        }

        public String allocationId() {
            return this.allocationId;
        }

        public boolean primary() {
            return this.primary;
        }

        public ReplicationCheckpoint replicationCheckpoint() {
            return this.replicationCheckpoint;
        }

        public Exception storeException() {
            return this.storeException;
        }

        public void writeTo(StreamOutput out) throws IOException {
            out.writeOptionalString(allocationId);
            out.writeBoolean(primary);
            if (storeException != null) {
                out.writeBoolean(true);
                out.writeException(storeException);
            } else {
                out.writeBoolean(false);
            }
            if (replicationCheckpoint != null) {
                out.writeBoolean(true);
                replicationCheckpoint.writeTo(out);
            } else {
                out.writeBoolean(false);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            NodeGatewayStartedShard that = (NodeGatewayStartedShard) o;

            return primary == that.primary
                && Objects.equals(allocationId, that.allocationId)
                && Objects.equals(storeException, that.storeException)
                && Objects.equals(replicationCheckpoint, that.replicationCheckpoint);
        }

        @Override
        public int hashCode() {
            int result = (allocationId != null ? allocationId.hashCode() : 0);
            result = 31 * result + (primary ? 1 : 0);
            result = 31 * result + (storeException != null ? storeException.hashCode() : 0);
            result = 31 * result + (replicationCheckpoint != null ? replicationCheckpoint.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            buf.append("NodeGatewayStartedShards[").append("allocationId=").append(allocationId).append(",primary=").append(primary);
            if (storeException != null) {
                buf.append(",storeException=").append(storeException);
            }
            if (replicationCheckpoint != null) {
                buf.append(",ReplicationCheckpoint=").append(replicationCheckpoint.toString());
            }
            buf.append("]");
            return buf.toString();
        }
    }

    /**
     * This is the response from a single node, this is used in {@link NodesGatewayStartedShardsBatch} for creating
     * node to its response mapping for this transport request.
     * Refer {@link TransportNodesAction} start method
     *
     * @opensearch.internal
     */
    public static class NodeGatewayStartedShardsBatch extends BaseNodeResponse {
        private final Map<ShardId, NodeGatewayStartedShard> nodeGatewayStartedShardsBatch;

        public Map<ShardId, NodeGatewayStartedShard> getNodeGatewayStartedShardsBatch() {
            return nodeGatewayStartedShardsBatch;
        }

        public NodeGatewayStartedShardsBatch(StreamInput in) throws IOException {
            super(in);
            this.nodeGatewayStartedShardsBatch = in.readMap(ShardId::new, NodeGatewayStartedShard::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeMap(nodeGatewayStartedShardsBatch, (o, k) -> k.writeTo(o), (o, v) -> v.writeTo(o));
        }

        public NodeGatewayStartedShardsBatch(DiscoveryNode node, Map<ShardId, NodeGatewayStartedShard> nodeGatewayStartedShardsBatch) {
            super(node);
            this.nodeGatewayStartedShardsBatch = nodeGatewayStartedShardsBatch;
        }
    }
}
