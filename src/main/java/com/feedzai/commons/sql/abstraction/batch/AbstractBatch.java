/*
 * Copyright 2014 Feedzai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.feedzai.commons.sql.abstraction.batch;

import com.feedzai.commons.sql.abstraction.engine.DatabaseEngine;
import com.feedzai.commons.sql.abstraction.engine.DatabaseEngineException;
import com.feedzai.commons.sql.abstraction.entry.EntityEntry;
import com.google.common.base.Strings;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A Batch that periodically flushes pending insertions to the database.
 * <p/>
 * Extending classes that want to be notified when a flush could not be performed after the timeout has been reached,
 * must override the {@link #onFlushFailure(BatchEntry[])}.
 *
 * @author Rui Vilao (rui.vilao@feedzai.com)
 * @since 2.0.0
 */
public abstract class AbstractBatch implements Runnable {
    /**
     * The logger.
     */
    protected final Logger logger = LoggerFactory.getLogger(AbstractBatch.class);
    /**
     * The dev Marker.
     */
    protected final static Marker dev = MarkerFactory.getMarker("DEV");
    /**
     * Salt to avoid erroneous flushes.
     */
    protected static final int salt = 100;

    /**
     * Lock used for concurrent access to the flush buffer
     *
     * @since 2.1.4
     */
    private final Lock flushLock = new ReentrantLock();
    /**
     * The database engine.
     */
    protected final DatabaseEngine de;
    /**
     * The maximum await time to wait for the batch to shutdown.
     */
    protected final long maxAwaitTimeShutdown;
    /**
     * The Timer that runs this task.
     */
    protected ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    /**
     * The batchSize.
     */
    protected final int batchSize;
    /**
     * The batch timeout.
     */
    protected final long batchTimeout;
    /**
     * The batch at the present moment.
     */
    protected int batch;
    /**
     * Timestamp of the last flush.
     */
    protected long lastFlush;
    /**
     * EntityEntry buffer.
     */
    protected LinkedList<BatchEntry> buffer = new LinkedList<>();
    /**
     * The name of the batch.
     */
    protected String name;

    /**
     * Creates a new instance of {@link AbstractBatch}.
     *
     * @param de                   The database engine.
     * @param name                 The batch name (null or empty names are allowed, falling back to "Anonymous Batch").
     * @param batchSize            The batch size.
     * @param batchTimeout         The batch timeout.
     * @param maxAwaitTimeShutdown The maximum await time for the batch to shutdown.
     */
    protected AbstractBatch(final DatabaseEngine de, String name, final int batchSize, final long batchTimeout, final long maxAwaitTimeShutdown) {
        this.de = de;
        this.batchSize = batchSize;
        this.batch = batchSize;
        this.batchTimeout = batchTimeout;
        this.lastFlush = System.currentTimeMillis();
        this.name = Strings.isNullOrEmpty(name) ? "Anonymous Batch" : name;
        this.maxAwaitTimeShutdown = maxAwaitTimeShutdown;
    }

    /**
     * Creates a new instance of {@link AbstractBatch}.
     *
     * @param de           The database engine.
     * @param batchSize    The batch size.
     * @param batchTimeout The batch timeout.
     */
    protected AbstractBatch(final DatabaseEngine de, final int batchSize, final long batchTimeout, final long maxAwaitTimeShutdown) {
        this(de, null, batchSize, batchTimeout, maxAwaitTimeShutdown);
    }

    /**
     * Starts the timer task.
     */
    protected void start() {
        scheduler.scheduleAtFixedRate(this, 0, batchTimeout + salt, TimeUnit.MILLISECONDS);
    }

    /**
     * Destroys this batch.
     */
    public synchronized void destroy() {
        logger.trace("{} - Destroy called on Batch", name);
        scheduler.shutdownNow();

        try {
            if (!scheduler.awaitTermination(maxAwaitTimeShutdown, TimeUnit.MILLISECONDS)) {
                logger.warn("Could not terminate batch within {}", DurationFormatUtils.formatDurationWords(maxAwaitTimeShutdown, true, true));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted while waiting.", e);
        }

        flush();
    }

    /**
     * Adds the fields to the batch.
     *
     * @param batchEntry The batch entry.
     * @throws DatabaseEngineException If an error with the database occurs.
     */
    public void add(BatchEntry batchEntry) throws DatabaseEngineException {
        flushLock.lock();
        try {
            buffer.add(batchEntry);
            batch--;
        } finally {
            flushLock.unlock();
        }
        if (batch <= 0) {
            flush();
        }
    }

    /**
     * Adds the fields to the batch.
     *
     * @param entityName The table name.
     * @param ee         The entity entry.
     * @throws DatabaseEngineException If an error with the database occurs.
     */
    public void add(final String entityName, final EntityEntry ee) throws DatabaseEngineException {
        add(new BatchEntry(entityName, ee));
    }

    /**
     * Flushes the pending batches.
     */
    public void flush() {
        List<BatchEntry> temp;

        flushLock.lock();
        try {
            // Reset the last flush timestamp, even if the batch is empty or flush fails
            lastFlush = System.currentTimeMillis();

            // No-op if batch is empty
            if (batch == batchSize) {
                logger.trace("[{}] Batch empty, not flushing", name);
                return;
            }

            // Declare the batch empty, regardless of flush success/failure
            batch = batchSize;

            // If something goes wrong we still have a copy to recover.
            temp = buffer;
            buffer = new LinkedList<>();

        } finally {
            flushLock.unlock();
        }

        try {
            final long start = System.currentTimeMillis();

            // begin the transaction before the addBatch calls in order to force the retry
            // of the connection if the same was lost during or since the last batch. Otherwise
            // the addBatch call that uses a prepared statement will fail
            de.beginTransaction();

            // This has to be separate because it accesses to the database.
            for (BatchEntry entry : temp) {
                de.addBatch(entry.getTableName(), entry.getEntityEntry());
            }

            try {
                de.flush();
                de.commit();
                logger.trace("[{}] Batch flushed. Took {} ms, {} rows.", name, (System.currentTimeMillis() - start), temp.size());
            } finally {
                if (de.isTransactionActive()) {
                    de.rollback();
                }
            }
        } catch (Exception e) {
            logger.error(dev, "[{}] Error occurred while flushing.", name, e);
            /*
             * We cannot try any recovery here because we don't know why it failed. If it failed by a Constraint
             * violation for instance, we cannot try it again and again because it will always fail.
             *
             * The idea here is to hand the entries that could not be added and continue right where we were.
             *
             * So, temp will only have the entries until it failed, this can be all the entries or until de.addBatch() failed;
             * and buffer will have the events right after the failure.
             */
            onFlushFailure(temp.toArray(new BatchEntry[temp.size()]));
        }

    }

    /**
     * Notifies about the pending entries on flush failure.
     *
     * @param entries The entries that are pending to be persisted.
     */
    public abstract void onFlushFailure(BatchEntry[] entries); {
        // NO-OP.
    }

    @Override
    public synchronized void run() {
        if (System.currentTimeMillis() - lastFlush >= batchTimeout) {
            logger.trace("[{}] Flush timeout occurred", name);
            flush();
        }
    }
}
