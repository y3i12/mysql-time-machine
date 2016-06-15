package com.booking.replication.coordinator;

import com.booking.replication.Configuration;
import com.booking.replication.checkpoints.LastVerifiedBinlogFile;
import com.booking.replication.checkpoints.SafeCheckPoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

/**
 * Created by bosko on 5/31/16.
 */
public class ZookeeperCoordinator implements CoordinatorInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperCoordinator.class);

    private final Configuration configuration;

    private volatile boolean isLeader = false;
    private volatile boolean isRunning = true;

    private SafeCheckPoint safeCheckPoint;

    private CuratorFramework client;

    private class CoordinatorLeaderElectionListener extends LeaderSelectorListenerAdapter implements Closeable {

        private final Runnable callback;
        private final LeaderSelector leaderSelector;

        public CoordinatorLeaderElectionListener(CuratorFramework client, String path, Runnable onLeadership) {
            super();
            leaderSelector = new LeaderSelector(client, path, this);

            callback = onLeadership;
        }

        public void start() {
            leaderSelector.start();
        }

        @Override
        public void takeLeadership(CuratorFramework curatorFramework) throws Exception {
            isLeader = true;
            LOGGER.info("Acquired leadership, starting Replicator.");

            try {
                callback.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
            isRunning = false;
        }

        @Override
        public void close() throws IOException {
            leaderSelector.close();
        }
    }

    public ZookeeperCoordinator(Configuration configuration) throws Exception {
        this.configuration = configuration;
        this.checkPointPath = String.format("%s/checkpoint", configuration.getZookeeperPath());

        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);

        String cluster = configuration.getZookeeperQuorum();
        if (cluster == null || "".equals(cluster)) {
            throw new Exception("expecting env ZOOKEEPER_CLUSTER (should be in /etc/sysconfig/bookings.puppet)");
        }

        client = CuratorFrameworkFactory.newClient(cluster, retryPolicy);
        client.start();

        client.createContainers(configuration.getZookeeperPath());
    }

    public synchronized void onLeaderElection(Runnable callback) throws InterruptedException {
        LOGGER.info("Waiting to become a leader.");

        CoordinatorLeaderElectionListener le = new CoordinatorLeaderElectionListener(
                client,
                String.format("%s/master", configuration.getZookeeperPath()),
                callback);

        le.start();

        while (!isLeader || isRunning) {
            Thread.sleep(1000);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String serialize(SafeCheckPoint checkPoint) throws JsonProcessingException {
        return mapper.writeValueAsString(checkPoint);
    }

    private final String checkPointPath;

    @Override
    public void storeSafeCheckPoint(SafeCheckPoint safeCheckPoint) throws Exception {
        // TODO: store in zk
        try {
            String serializedCP = serialize(safeCheckPoint);

            Stat exists = client.checkExists().forPath(checkPointPath);
            if ( exists != null ) {
                client.setData().forPath(checkPointPath, serializedCP.getBytes());
            } else {
                client.create().withMode(CreateMode.PERSISTENT).forPath(checkPointPath, serializedCP.getBytes());
            }
            LOGGER.info(String.format("Stored information in ZK: %s", serializedCP));
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to serialize safeCheckPoint!");
            throw e;
        } catch (Exception e ) {
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    public SafeCheckPoint getSafeCheckPoint() {
        // TODO: get from zk

        try {
            if (client.checkExists().forPath(checkPointPath) == null) {
                LOGGER.warn("Could not find metadata in zookeeper.");
                return null;
            }
            byte[] data = client.getData().forPath(checkPointPath);
            return mapper.readValue(data, LastVerifiedBinlogFile.class);
        } catch (JsonProcessingException e) {
            LOGGER.error(String.format("Failed to deserialize checkpoint data. %s", e.getMessage()));
            e.printStackTrace();
        } catch (Exception e) {
            LOGGER.error(String.format("Got an error while reading metadata from Zookeeper: %s", e.getMessage()));
            e.printStackTrace();
        }

        return null;
    }

}
