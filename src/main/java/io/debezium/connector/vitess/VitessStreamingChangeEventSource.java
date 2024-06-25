/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.vitess;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.openjdk.jol.info.ClassLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.vitess.connection.ReplicationConnection;
import io.debezium.connector.vitess.connection.ReplicationMessage;
import io.debezium.connector.vitess.connection.ReplicationMessageProcessor;
import io.debezium.connector.vitess.pipeline.txmetadata.VitessTransactionInfo;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;
import io.debezium.util.DelayStrategy;

/**
 * Read events from source and dispatch each event using {@link EventDispatcher} to the {@link
 * io.debezium.pipeline.source.spi.ChangeEventSource}. It runs in the
 * change-event-source-coordinator thread only.
 */
public class VitessStreamingChangeEventSource implements StreamingChangeEventSource<VitessPartition, VitessOffsetContext> {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitessStreamingChangeEventSource.class);

    private final EventDispatcher<VitessPartition, TableId> dispatcher;
    private final ErrorHandler errorHandler;
    private final Clock clock;
    private final VitessDatabaseSchema schema;
    private final VitessConnectorConfig connectorConfig;
    private final ReplicationConnection replicationConnection;
    private final DelayStrategy pauseNoMessage;

    public VitessStreamingChangeEventSource(
                                            EventDispatcher<VitessPartition, TableId> dispatcher,
                                            ErrorHandler errorHandler,
                                            Clock clock,
                                            VitessDatabaseSchema schema,
                                            VitessConnectorConfig connectorConfig,
                                            ReplicationConnection replicationConnection) {
        this.dispatcher = dispatcher;
        this.errorHandler = errorHandler;
        this.clock = clock;
        this.schema = schema;
        this.connectorConfig = connectorConfig;
        this.replicationConnection = replicationConnection;
        this.pauseNoMessage = DelayStrategy.constant(connectorConfig.getPollInterval());

        LOGGER.info("VitessStreamingChangeEventSource is created");
    }

    @Override
    public void execute(ChangeEventSourceContext context, VitessPartition partition, VitessOffsetContext offsetContext) {
        if (offsetContext == null) {
            offsetContext = VitessOffsetContext.initialContext(connectorConfig, clock);
        }

        try {
            AtomicReference<Throwable> error = new AtomicReference<>();
            replicationConnection.startStreaming(
                    offsetContext.getRestartVgtid(), newReplicationMessageProcessor(partition, offsetContext), error);

            while (context.isRunning() && error.get() == null) {
                pauseNoMessage.sleepWhen(true);
            }
            if (error.get() != null) {
                LOGGER.error("Error during streaming", error.get());
                throw error.get();
            }
        }
        catch (Throwable e) {
            errorHandler.setProducerThrowable(e);
        }
        finally {
            try {
                // closing the connection should also disconnect the VStream gRPC channel
                replicationConnection.close();
            }
            catch (Exception e) {
                LOGGER.error("Failed to close replicationConnection", e);
            }
        }
    }

    private ReplicationMessageProcessor newReplicationMessageProcessor(VitessPartition partition,
                                                                       VitessOffsetContext offsetContext) {
        return (message, newVgtid, isLastRowOfTransaction) -> {
            if (message.isTransactionalMessage()) {
                // Tx BEGIN/END event
                offsetContext.rotateVgtid(newVgtid, message.getCommitTime());
                if (message.getOperation() == ReplicationMessage.Operation.BEGIN) {
                    // send to transaction topic
                    VitessTransactionInfo transactionInfo = new VitessTransactionInfo(message.getTransactionId(), message.getShard());
                    dispatcher.dispatchTransactionStartedEvent(partition, transactionInfo, offsetContext, message.getCommitTime());
                }
                else if (message.getOperation() == ReplicationMessage.Operation.COMMIT) {
                    // send to transaction topic
                    dispatcher.dispatchTransactionCommittedEvent(partition, offsetContext, message.getCommitTime());
                }
                return;
            }
            else if (message.getOperation() == ReplicationMessage.Operation.DDL || message.getOperation() == ReplicationMessage.Operation.OTHER) {
                // DDL event or OTHER event
                offsetContext.rotateVgtid(newVgtid, message.getCommitTime());
            }
            else {
                // DML event
                TableId tableId = VitessDatabaseSchema.parse(message.getTable());
                Objects.requireNonNull(tableId);

                offsetContext.event(tableId, message.getCommitTime());
                offsetContext.setShard(message.getShard());
                if (isLastRowOfTransaction) {
                    // Right before processing the last row, reset the previous offset to the new vgtid so the last row has the new vgtid as offset.
                    offsetContext.resetVgtid(newVgtid, message.getCommitTime());
                }
                if (isMessageForByfile(message)) {
                    LOGGER.warn("Message is for byfile, bytes: {}, message: {}", getSizeBytes(message), message);
                }
                if (isMessageForByfile(message) && getSizeBytes(message) > 10 * 1000) {
                    LOGGER.warn("Big message is for byfile, bytes: {}, message: {}", getSizeBytes(message), message);
                }
                dispatcher.dispatchDataChangeEvent(
                        partition,
                        tableId,
                        new VitessChangeRecordEmitter(
                                partition, offsetContext, clock, connectorConfig, schema, message));
            }
        };
    }

    public long getSizeBytes(Object record) {
        return ClassLayout.parseInstance(record).instanceSize();
    }

    public boolean isMessageForByfile(ReplicationMessage message) {
        return message.getTable().contains("files");
    }

}
