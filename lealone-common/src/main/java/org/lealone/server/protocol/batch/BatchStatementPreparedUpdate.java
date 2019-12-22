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
package org.lealone.server.protocol.batch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.lealone.db.value.Value;
import org.lealone.net.NetInputStream;
import org.lealone.net.NetOutputStream;
import org.lealone.server.protocol.Packet;
import org.lealone.server.protocol.PacketDecoder;
import org.lealone.server.protocol.PacketType;

public class BatchStatementPreparedUpdate implements Packet {

    public final int commandId;
    public final int size;
    public final List<Value[]> batchParameters;

    public BatchStatementPreparedUpdate(int commandId, int size, List<Value[]> batchParameters) {
        this.commandId = commandId;
        this.size = size;
        this.batchParameters = batchParameters;
    }

    @Override
    public PacketType getType() {
        return PacketType.COMMAND_BATCH_STATEMENT_PREPARED_UPDATE;
    }

    @Override
    public PacketType getAckType() {
        return PacketType.COMMAND_BATCH_STATEMENT_UPDATE_ACK;
    }

    @Override
    public void encode(NetOutputStream out, int version) throws IOException {
        out.writeInt(commandId);
        int size = batchParameters.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            Value[] values = batchParameters.get(i);
            int len = values.length;
            out.writeInt(len);
            for (int j = 0; j < len; j++)
                out.writeValue(values[j]);
        }
    }

    public static final Decoder decoder = new Decoder();

    private static class Decoder implements PacketDecoder<BatchStatementPreparedUpdate> {
        @Override
        public BatchStatementPreparedUpdate decode(NetInputStream in, int version) throws IOException {
            int commandId = in.readInt();
            int size = in.readInt();
            ArrayList<Value[]> batchParameters = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int len = in.readInt();
                Value[] values = new Value[len];
                for (int j = 0; j < len; j++)
                    values[j] = in.readValue();
                batchParameters.add(values);
            }
            return new BatchStatementPreparedUpdate(commandId, size, batchParameters);
        }
    }
}
