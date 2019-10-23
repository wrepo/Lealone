/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lealone.client.result.ClientResult;
import org.lealone.client.result.RowCountDeterminedClientResult;
import org.lealone.client.result.RowCountUndeterminedClientResult;
import org.lealone.db.CommandParameter;
import org.lealone.db.CommandUpdateResult;
import org.lealone.db.Session;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.result.Result;
import org.lealone.db.value.ValueLong;
import org.lealone.net.AsyncCallback;
import org.lealone.net.TransferInputStream;
import org.lealone.net.TransferOutputStream;
import org.lealone.storage.LeafPageMovePlan;
import org.lealone.storage.PageKey;
import org.lealone.storage.StorageCommand;

/**
 * Represents the client-side part of a SQL statement.
 * This class is not used in embedded mode.
 * 
 * @author H2 Group
 * @author zhh
 */
public class ClientCommand implements StorageCommand {

    // 通过设为null来判断是否关闭了当前命令，所以没有加上final
    protected ClientSession session;
    protected final String sql;
    protected final int fetchSize;
    protected int packetId;
    protected boolean isQuery;

    public ClientCommand(ClientSession session, String sql, int fetchSize) {
        this.session = session;
        this.sql = sql;
        this.fetchSize = fetchSize;
    }

    @Override
    public int getType() {
        return CLIENT_COMMAND;
    }

    @Override
    public boolean isQuery() {
        return isQuery;
    }

    @Override
    public ArrayList<CommandParameter> getParameters() {
        return new ArrayList<>(0);
    }

    @Override
    public Result getMetaData() {
        return null;
    }

    @Override
    public Result executeQuery(int maxRows) {
        return query(maxRows, false, null, null);
    }

    @Override
    public Result executeQuery(int maxRows, boolean scrollable) {
        return query(maxRows, scrollable, null, null);
    }

    @Override
    public Result executeQuery(int maxRows, boolean scrollable, List<PageKey> pageKeys) {
        return query(maxRows, scrollable, pageKeys, null);
    }

    @Override
    public void executeQueryAsync(int maxRows, boolean scrollable, AsyncHandler<AsyncResult<Result>> handler) {
        query(maxRows, scrollable, null, handler);
    }

    @Override
    public void executeQueryAsync(int maxRows, boolean scrollable, List<PageKey> pageKeys,
            AsyncHandler<AsyncResult<Result>> handler) {
        query(maxRows, scrollable, pageKeys, handler);
    }

    protected Result query(int maxRows, boolean scrollable, List<PageKey> pageKeys,
            AsyncHandler<AsyncResult<Result>> handler) {
        packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        int resultId = session.getNextId();
        Result result = null;
        try {
            boolean isDistributedQuery = isDistributed();
            if (isDistributedQuery) {
                session.traceOperation("COMMAND_DISTRIBUTED_TRANSACTION_QUERY", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_DISTRIBUTED_TRANSACTION_QUERY);
            } else {
                session.traceOperation("COMMAND_QUERY", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_QUERY);
            }
            int fetch;
            if (scrollable) {
                fetch = Integer.MAX_VALUE;
            } else {
                fetch = fetchSize;
            }
            out.writeInt(resultId).writeInt(maxRows).writeInt(fetch).writeBoolean(scrollable);
            out.writeString(sql);
            writePageKeys(pageKeys, out);

            result = getQueryResult(isDistributedQuery, fetch, resultId, handler, out);
        } catch (Exception e) {
            session.handleException(e);
        }
        return result;
    }

    protected boolean isDistributed() {
        return session.getParentTransaction() != null && !session.getParentTransaction().isAutoCommit();
    }

    protected void writePageKeys(List<PageKey> pageKeys, TransferOutputStream out) throws IOException {
        if (pageKeys == null) {
            out.writeInt(0);
        } else {
            int size = pageKeys.size();
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                PageKey pk = pageKeys.get(i);
                out.writePageKey(pk);
            }
        }
    }

    protected Result getQueryResult(boolean isDistributedQuery, int fetch, int resultId,
            AsyncHandler<AsyncResult<Result>> handler, TransferOutputStream out) throws IOException {
        isQuery = true;
        AsyncCallback<ClientResult> ac = new AsyncCallback<ClientResult>() {
            @Override
            public void runInternal(TransferInputStream in) throws Exception {
                if (isDistributedQuery)
                    session.getParentTransaction().addLocalTransactionNames(in.readString());

                int columnCount = in.readInt();
                int rowCount = in.readInt();
                ClientResult result;
                if (rowCount < 0)
                    result = new RowCountUndeterminedClientResult(session, in, resultId, columnCount, fetch);
                else
                    result = new RowCountDeterminedClientResult(session, in, resultId, columnCount, rowCount, fetch);
                setResult(result);
                if (handler != null) {
                    AsyncResult<Result> r = new AsyncResult<>();
                    r.setResult(result);
                    handler.handle(r);
                }
            }
        };
        if (handler != null) {
            ac.setAsyncHandler(handler);
            out.flush(packetId, ac);
            return null;
        } else {
            return out.flushAndAwait(packetId, ac);
        }
    }

    @Override
    public int executeUpdate() {
        return update(null, null, null, null);
    }

    @Override
    public int executeUpdate(List<PageKey> pageKeys) {
        return update(null, null, pageKeys, null);
    }

    @Override
    public int executeUpdate(String replicationName, CommandUpdateResult commandUpdateResult) {
        return update(replicationName, commandUpdateResult, null, null);
    }

    @Override
    public void executeUpdateAsync(AsyncHandler<AsyncResult<Integer>> handler) {
        update(null, null, null, handler);
    }

    protected int update(String replicationName, CommandUpdateResult commandUpdateResult, List<PageKey> pageKeys,
            AsyncHandler<AsyncResult<Integer>> handler) {
        packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        int updateCount = 0;
        try {
            boolean isDistributedUpdate = isDistributed();
            if (isDistributedUpdate) {
                session.traceOperation("COMMAND_DISTRIBUTED_TRANSACTION_UPDATE", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_DISTRIBUTED_TRANSACTION_UPDATE);
            } else if (replicationName != null) {
                session.traceOperation("COMMAND_REPLICATION_UPDATE", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_REPLICATION_UPDATE);
            } else {
                session.traceOperation("COMMAND_UPDATE", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_UPDATE);
            }
            if (replicationName != null)
                out.writeString(replicationName);
            out.writeString(sql);
            writePageKeys(pageKeys, out);
            updateCount = getUpdateCount(isDistributedUpdate, packetId, commandUpdateResult, handler, out);
        } catch (Exception e) {
            session.handleException(e);
        }
        return updateCount;
    }

    protected int getUpdateCount(boolean isDistributedUpdate, int packetId, CommandUpdateResult commandUpdateResult,
            AsyncHandler<AsyncResult<Integer>> handler, TransferOutputStream out) throws IOException {
        isQuery = false;
        AsyncCallback<Integer> ac = new AsyncCallback<Integer>() {
            @Override
            public void runInternal(TransferInputStream in) throws Exception {
                if (isDistributedUpdate)
                    session.getParentTransaction().addLocalTransactionNames(in.readString());

                int updateCount = in.readInt();
                long key = in.readLong();
                if (commandUpdateResult != null) {
                    commandUpdateResult.setUpdateCount(updateCount);
                    commandUpdateResult.addResult(ClientCommand.this, key);
                }
                setResult(updateCount);
                if (handler != null) {
                    AsyncResult<Integer> r = new AsyncResult<>();
                    r.setResult(updateCount);
                    handler.handle(r);
                }
            }
        };
        int updateCount;
        if (handler != null) {
            updateCount = -1;
            ac.setAsyncHandler(handler);
            out.flush(packetId, ac);
        } else {
            updateCount = out.flushAndAwait(packetId, ac);
        }
        return updateCount;
    }

    @Override
    public void close() {
        session = null;
    }

    /**
     * Cancel this current statement.
     */
    @Override
    public void cancel() {
        session.cancelStatement(packetId);
    }

    @Override
    public String toString() {
        return sql;
    }

    int getId() {
        return packetId;
    }

    String getSql() {
        return sql;
    }

    @Override
    public Object executePut(String replicationName, String mapName, ByteBuffer key, ByteBuffer value, boolean raw) {
        byte[] bytes = null;
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            boolean isDistributed = session.getParentTransaction() != null
                    && !session.getParentTransaction().isAutoCommit();
            if (isDistributed) {
                session.traceOperation("COMMAND_STORAGE_DISTRIBUTED_TRANSACTION_PUT", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_DISTRIBUTED_TRANSACTION_PUT);
            } else if (replicationName != null) {
                session.traceOperation("COMMAND_STORAGE_REPLICATION_PUT", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_REPLICATION_PUT);
            } else {
                session.traceOperation("COMMAND_STORAGE_PUT", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_PUT);
            }
            out.writeString(mapName).writeByteBuffer(key).writeByteBuffer(value);
            out.writeString(replicationName).writeBoolean(raw);
            bytes = out.flushAndAwait(packetId, new AsyncCallback<byte[]>() {
                @Override
                public void runInternal(TransferInputStream in) throws Exception {
                    if (isDistributed)
                        session.getParentTransaction().addLocalTransactionNames(in.readString());
                    setResult(in.readBytes());
                }
            });
        } catch (Exception e) {
            session.handleException(e);
        }
        return bytes;
    }

    @Override
    public Object executeGet(String mapName, ByteBuffer key) {
        byte[] bytes = null;
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            boolean isDistributed = session.getParentTransaction() != null
                    && !session.getParentTransaction().isAutoCommit();
            if (isDistributed) {
                session.traceOperation("COMMAND_STORAGE_DISTRIBUTED_TRANSACTION_GET", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_DISTRIBUTED_TRANSACTION_GET);
            } else {
                session.traceOperation("COMMAND_STORAGE_GET", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_GET);
            }
            out.writeString(mapName).writeByteBuffer(key);
            bytes = out.flushAndAwait(packetId, new AsyncCallback<byte[]>() {
                @Override
                public void runInternal(TransferInputStream in) throws Exception {
                    if (isDistributed)
                        session.getParentTransaction().addLocalTransactionNames(in.readString());
                    setResult(in.readBytes());
                }
            });
        } catch (Exception e) {
            session.handleException(e);
        }
        return bytes;
    }

    @Override
    public void moveLeafPage(String mapName, PageKey pageKey, ByteBuffer page, boolean addPage) {
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            session.traceOperation("COMMAND_STORAGE_MOVE_LEAF_PAGE", packetId);
            out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_MOVE_LEAF_PAGE);
            out.writeString(mapName).writePageKey(pageKey).writeByteBuffer(page).writeBoolean(addPage);
            out.flush();
        } catch (Exception e) {
            session.handleException(e);
        }
    }

    @Override
    public void replicateRootPages(String dbName, ByteBuffer rootPages) {
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            session.traceOperation("COMMAND_STORAGE_REPLICATE_ROOT_PAGES", packetId);
            out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_REPLICATE_ROOT_PAGES);
            out.writeString(dbName).writeByteBuffer(rootPages);
            out.flush();
        } catch (Exception e) {
            session.handleException(e);
        }
    }

    @Override
    public void removeLeafPage(String mapName, PageKey pageKey) {
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            session.traceOperation("COMMAND_STORAGE_REMOVE_LEAF_PAGE", packetId);
            out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_REMOVE_LEAF_PAGE);
            out.writeString(mapName).writePageKey(pageKey);
            out.flush();
        } catch (Exception e) {
            session.handleException(e);
        }
    }

    @Override
    public Object executeAppend(String replicationName, String mapName, ByteBuffer value,
            CommandUpdateResult commandUpdateResult) {
        Long result = null;
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            boolean isDistributed = session.getParentTransaction() != null
                    && !session.getParentTransaction().isAutoCommit();
            if (isDistributed) {
                session.traceOperation("COMMAND_STORAGE_DISTRIBUTED_TRANSACTION_APPEND", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_DISTRIBUTED_TRANSACTION_APPEND);
            } else {
                session.traceOperation("COMMAND_STORAGE_APPEND", packetId);
                out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_APPEND);
            }
            out.writeString(mapName).writeByteBuffer(value);
            out.writeString(replicationName);

            result = out.flushAndAwait(packetId, new AsyncCallback<Long>() {
                @Override
                public void runInternal(TransferInputStream in) throws Exception {
                    if (isDistributed)
                        session.getParentTransaction().addLocalTransactionNames(in.readString());
                    setResult(in.readLong());
                }
            });
        } catch (Exception e) {
            session.handleException(e);
        }
        commandUpdateResult.addResult(this, result);
        return ValueLong.get(result);
    }

    @Override
    public void replicationCommit(long validKey, boolean autoCommit) {
        int packetId = session.getNextId();
        session.traceOperation("COMMAND_REPLICATION_COMMIT", packetId);
        TransferOutputStream out = session.newOut();
        try {
            out.writeRequestHeader(packetId, Session.COMMAND_REPLICATION_COMMIT);
            out.writeLong(validKey).writeBoolean(autoCommit).flush();
        } catch (IOException e) {
            session.getTrace().error(e, "replicationCommit");
        }
    }

    @Override
    public void replicationRollback() {
        int packetId = session.getNextId();
        session.traceOperation("COMMAND_REPLICATION_ROLLBACK", packetId);
        try {
            session.newOut().writeRequestHeader(packetId, Session.COMMAND_REPLICATION_ROLLBACK).flush();
        } catch (IOException e) {
            session.getTrace().error(e, "replicationRollback");
        }
    }

    @Override
    public LeafPageMovePlan prepareMoveLeafPage(String mapName, LeafPageMovePlan leafPageMovePlan) {
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            session.traceOperation("COMMAND_STORAGE_PREPARE_MOVE_LEAF_PAGE", packetId);
            out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_PREPARE_MOVE_LEAF_PAGE);
            out.writeString(mapName);
            leafPageMovePlan.serialize(out);
            return out.flushAndAwait(packetId, new AsyncCallback<LeafPageMovePlan>() {
                @Override
                public void runInternal(TransferInputStream in) throws Exception {
                    setResult(LeafPageMovePlan.deserialize(in));
                }
            });
        } catch (Exception e) {
            session.handleException(e);
        }
        return null;
    }

    @Override
    public ByteBuffer readRemotePage(String mapName, PageKey pageKey) {
        int packetId = session.getNextId();
        TransferOutputStream out = session.newOut();
        try {
            session.traceOperation("COMMAND_STORAGE_READ_PAGE", packetId);
            out.writeRequestHeader(packetId, Session.COMMAND_STORAGE_READ_PAGE);
            out.writeString(mapName).writePageKey(pageKey);
            return out.flushAndAwait(packetId, new AsyncCallback<ByteBuffer>() {
                @Override
                public void runInternal(TransferInputStream in) throws Exception {
                    result = in.readByteBuffer();
                }
            });
        } catch (Exception e) {
            session.handleException(e);
        }
        return null;
    }
}
