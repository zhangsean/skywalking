/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao;

import java.sql.*;
import java.util.List;
import org.apache.skywalking.apm.commons.datacarrier.DataCarrier;
import org.apache.skywalking.apm.commons.datacarrier.consumer.*;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.storage.IBatchDAO;
import org.apache.skywalking.oap.server.library.client.jdbc.JDBCClientException;
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient;
import org.apache.skywalking.oap.server.library.client.request.*;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.SQLExecutor;
import org.slf4j.*;

/**
 * @author wusheng, peng-yongsheng
 */
public class H2BatchDAO implements IBatchDAO {

    private static final Logger logger = LoggerFactory.getLogger(H2BatchDAO.class);

    private JDBCHikariCPClient h2Client;
    private final DataCarrier<PrepareRequest> dataCarrier;

    public H2BatchDAO(JDBCHikariCPClient h2Client) {
        this.h2Client = h2Client;

        String name = "H2_ASYNCHRONOUS_BATCH_PERSISTENT";
        BulkConsumePool.Creator creator = new BulkConsumePool.Creator(name, 1, 20);
        try {
            ConsumerPoolFactory.INSTANCE.createIfAbsent(name, creator);
        } catch (Exception e) {
            throw new UnexpectedException(e.getMessage(), e);
        }

        this.dataCarrier = new DataCarrier<>(1, 10000);
        this.dataCarrier.consume(ConsumerPoolFactory.INSTANCE.get(name), new H2BatchDAO.H2BatchConsumer(this));
    }

    @Override public void synchronous(List<PrepareRequest> prepareRequests) {
        if (CollectionUtils.isEmpty(prepareRequests)) {
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("batch sql statements execute, data size: {}", prepareRequests.size());
        }

        try (Connection connection = h2Client.getConnection()) {
            for (PrepareRequest prepareRequest : prepareRequests) {
                try {
                    SQLExecutor sqlExecutor = (SQLExecutor)prepareRequest;
                    sqlExecutor.invoke(connection);
                } catch (SQLException e) {
                    // Just avoid one execution failure makes the rest of batch failure.
                    logger.error(e.getMessage(), e);
                }
            }
        } catch (SQLException | JDBCClientException e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override public void asynchronous(InsertRequest insertRequest) {
        this.dataCarrier.produce(insertRequest);
    }

    private class H2BatchConsumer implements IConsumer<PrepareRequest> {

        private final H2BatchDAO h2BatchDAO;

        private H2BatchConsumer(H2BatchDAO h2BatchDAO) {
            this.h2BatchDAO = h2BatchDAO;
        }

        @Override public void init() {

        }

        @Override public void consume(List<PrepareRequest> prepareRequests) {
            h2BatchDAO.synchronous(prepareRequests);
        }

        @Override public void onError(List<PrepareRequest> prepareRequests, Throwable t) {
            logger.error(t.getMessage(), t);
        }

        @Override public void onExit() {
        }
    }
}
