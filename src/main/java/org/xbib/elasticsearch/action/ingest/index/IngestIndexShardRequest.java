
package org.xbib.elasticsearch.action.ingest.index;

import org.elasticsearch.action.support.replication.ShardReplicationOperationRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.common.collect.Lists.newLinkedList;

public class IngestIndexShardRequest extends ShardReplicationOperationRequest {

    private int shardId;

    private List<IngestIndexItemRequest> items;

    public IngestIndexShardRequest() {
    }

    public IngestIndexShardRequest(String index, int shardId, List<IngestIndexItemRequest> items) {
        this.index = index;
        this.shardId = shardId;
        this.items = items;
    }

    public int shardId() {
        return shardId;
    }

    public List<IngestIndexItemRequest> items() {
        return items;
    }

    /**
     * Before we fork on a local thread, make sure we copy over the bytes if they are unsafe
     */
    @Override
    public void beforeLocalFork() {
        for (IngestIndexItemRequest item : items) {
            ((ShardReplicationOperationRequest) item.request()).beforeLocalFork();
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(shardId);
        out.writeVInt(items.size());
        for (IngestIndexItemRequest item : items) {
            if (item != null) {
                out.writeBoolean(true);
                item.writeTo(out);
            } else {
                out.writeBoolean(false);
            }
        }
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        shardId = in.readVInt();
        int size = in.readVInt();
        items = newLinkedList();
        for (int i = 0; i < size; i++) {
            if (in.readBoolean()) {
                items.add(IngestIndexItemRequest.readBulkItem(in));
            }
        }
    }
}
