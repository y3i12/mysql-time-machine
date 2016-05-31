package com.booking.replication;

import com.booking.replication.applier.Applier;
import com.booking.replication.applier.HBaseApplier;
import com.booking.replication.applier.STDOUTJSONApplier;
import com.booking.replication.checkpoints.SafeCheckPoint;
import com.booking.replication.checkpoints.SafeCheckPointFactory;
import com.booking.replication.checkpoints.SafeCheckpointType;
import com.booking.replication.metrics.ReplicatorMetrics;
import com.booking.replication.pipeline.BinlogEventProducer;
import com.booking.replication.pipeline.PipelineOrchestrator;
import com.booking.replication.pipeline.BinlogPositionInfo;
import com.booking.replication.monitor.Overseer;

import com.booking.replication.queues.ReplicatorQueues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Booking replicator. Has two main objects (producer and consumer)
 * that reference the same thread-safe queue.
 * Producer pushes binlog events to the queue and consumer
 * reads them. Producer is basically a wrapper for open replicator,
 * and consumer is wrapper for all booking specific logic (schema
 * version control, augmenting events and storing events).
 */
public class Replicator {

    private final BinlogEventProducer  binlogEventProducer;
    private final PipelineOrchestrator pipelineOrchestrator;
    private final Overseer             overseer;

    private static final Logger LOGGER = LoggerFactory.getLogger(Replicator.class);

    // Replicator()
    public Replicator(Configuration configuration) throws SQLException, URISyntaxException, IOException {

        // Safe Check Point
        SafeCheckPoint safeCheckPoint = SafeCheckPointFactory.getSafeCheckPoint(SafeCheckpointType.BINLOG_FILENAME);
        safeCheckPoint.setSafeCheckPointMarker(configuration.getStartingBinlogFileName());

        // Queues
        ReplicatorQueues replicatorQueues = new ReplicatorQueues();

        // Position Tracking
        ConcurrentHashMap<Integer, Object> lastKnownInfo = new ConcurrentHashMap<Integer, Object>();

        lastKnownInfo.put(Constants.LAST_KNOWN_BINLOG_POSITION,
                new BinlogPositionInfo(
                    configuration.getStartingBinlogFileName(),
                    configuration.getStartingBinlogPosition()
                ));

        // Producer
        binlogEventProducer = new BinlogEventProducer(replicatorQueues.rawQueue, lastKnownInfo, configuration);

        // Metrics
        ReplicatorMetrics replicatorMetrics = new ReplicatorMetrics(configuration);

        // Applier
        Applier applier;

        if (configuration.getApplierType().equals("STDOUT")) {
            applier = new STDOUTJSONApplier(
                    replicatorQueues,
                    replicatorMetrics,
                    configuration
            );
        }
        else if (configuration.getApplierType().toLowerCase().equals("hbase")) {
            applier = new HBaseApplier(
                    replicatorQueues,
                    configuration.getHBaseQuorum(),
                    replicatorMetrics,
                    configuration
            );
        }
        else {
            LOGGER.warn("Unknown applier type. Defaulting to STDOUT");
            applier = new STDOUTJSONApplier(
                    replicatorQueues,
                    replicatorMetrics,
                    configuration
            );
        }

        // Orchestrator
        pipelineOrchestrator = new PipelineOrchestrator(
                replicatorQueues,
                lastKnownInfo,
                configuration,
                replicatorMetrics,
                applier
        );

        // Overseer
        overseer = new Overseer(
                binlogEventProducer,
                pipelineOrchestrator,
                replicatorMetrics,
                lastKnownInfo
        );
    }

    // start()
    public void start() throws Exception {

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                LOGGER.info("Executing replicator shutdown hook...");
                // Overseer
                try {
                    LOGGER.info("Stopping Overseer...");
                    overseer.stopMonitoring();
                    overseer.join();
                    LOGGER.info("Overseer thread successfully stopped");
                } catch (InterruptedException e) {
                    LOGGER.error("Interrupted.", e);
                } catch (Exception e) {
                    LOGGER.error("Failed to stop Overseer", e);
                }
                // Producer
                try {
                    // let open replicator stop its own threads
                    LOGGER.info("Stopping Producer...");
                    binlogEventProducer.stop(1000, TimeUnit.MILLISECONDS);
                    if (!binlogEventProducer.getOr().isRunning()) {
                        LOGGER.info("Successfully stopped Producer thread");
                    }
                    else {
                        throw new Exception("Failed to stop Producer thread");
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("Interrupted.", e);
                } catch (Exception e) {
                    LOGGER.error("Failed to stop Producer thread", e);
                }
                // Consumer
                try {
                    LOGGER.info("Stopping Pipeline Orchestrator...");
                    pipelineOrchestrator.setRunning(false);
                    pipelineOrchestrator.join();
                    LOGGER.info("Pipeline Orchestrator successfully stopped");
                } catch (InterruptedException e) {
                    LOGGER.error("Interrupted.", e);
                } catch (Exception e) {
                    LOGGER.error("Failed to stop Pipeline Orchestrator", e);
                }
            }
        });

        // Start up
        binlogEventProducer.start();
        pipelineOrchestrator.start();
        overseer.start();

        while (!pipelineOrchestrator.isReplicatorShutdownRequested()) {
            Thread.sleep(1000);
        }

        System.exit(0);
    }
}
