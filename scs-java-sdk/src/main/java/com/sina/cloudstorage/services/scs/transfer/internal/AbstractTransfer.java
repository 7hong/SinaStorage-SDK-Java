/*
 * Copyright 2012-2013 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sina.cloudstorage.services.scs.transfer.internal;

import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.sina.cloudstorage.SCSClientException;
import com.sina.cloudstorage.SCSServiceException;
import com.sina.cloudstorage.event.ProgressEvent;
import com.sina.cloudstorage.event.ProgressListener;
import com.sina.cloudstorage.event.ProgressListenerCallbackExecutor;
import com.sina.cloudstorage.event.ProgressListenerChain;
import com.sina.cloudstorage.services.scs.model.LegacyS3ProgressListener;
import com.sina.cloudstorage.services.scs.transfer.Transfer;
import com.sina.cloudstorage.services.scs.transfer.TransferProgress;


/**
 * Abstract transfer implementation.
 */
public abstract class AbstractTransfer implements Transfer {

    /** The current state of this transfer. */
    protected volatile TransferState state = TransferState.Waiting;

    protected TransferMonitor monitor;

    /** The progress of this transfer. */
    private final TransferProgress transferProgress;

    private final String description;

    /** Hook for adding/removing more progress listeners. */
    protected final ProgressListenerChain progressListenerChain;
    
    /** Listener call-back executor **/
    protected final ProgressListenerCallbackExecutor progressListenerChainCallbackExecutor;
    
    /** Collection of listeners to be notified for changes to the state of this transfer via setState() */
    protected final Collection<TransferStateChangeListener> stateChangeListeners = new LinkedList<TransferStateChangeListener>();
    
    AbstractTransfer(String description, TransferProgress transferProgress, ProgressListenerChain progressListenerChain) {
        this(description, transferProgress, progressListenerChain, null);
    }
    
    /**
     * @deprecated Replaced by {@link #AbstractTransfer(String, TransferProgress, ProgressListenerChain)}
     */
    @Deprecated
    AbstractTransfer(String description, TransferProgress transferProgress, com.sina.cloudstorage.services.scs.transfer.internal.ProgressListenerChain progressListenerChain) {
        this(description, transferProgress, progressListenerChain, null);
    }

    AbstractTransfer(String description, TransferProgress transferProgress,
            ProgressListenerChain progressListenerChain, TransferStateChangeListener stateChangeListener) {
        this.description = description;
        this.progressListenerChain = progressListenerChain;
        this.progressListenerChainCallbackExecutor = ProgressListenerCallbackExecutor
                .wrapListener(progressListenerChain);
        this.transferProgress = transferProgress;
        addStateChangeListener(stateChangeListener);
    }
    
    /**
     * @deprecated Replaced by {@link #AbstractTransfer(String, TransferProgress, ProgressListenerChain)}
     */
    @Deprecated
    AbstractTransfer(String description, TransferProgress transferProgress,
            com.sina.cloudstorage.services.scs.transfer.internal.ProgressListenerChain progressListenerChain, TransferStateChangeListener stateChangeListener) {
        this(description, transferProgress, progressListenerChain.transformToGeneralProgressListenerChain(),
                stateChangeListener);
    }
    
    /**
     * Returns whether or not the transfer is finished (i.e. completed successfully,
     * failed, or was canceled).
     *
     * @return Returns <code>true</code> if this transfer is finished (i.e. completed successfully,
     *         failed, or was canceled).  Returns <code>false</code> if otherwise.
     */
    public synchronized boolean isDone() {
        return (state == TransferState.Failed ||
                state == TransferState.Completed ||
                state == TransferState.Canceled);
    }

    /**
     * Waits for this transfer to complete. This is a blocking call; the current
     * thread is suspended until this transfer completes.
     *
     * @throws SCSClientException
     *             If any errors were encountered in the client while making the
     *             request or handling the response.
     * @throws SCSServiceException
     *             If any errors occurred in Amazon S3 while processing the
     *             request.
     * @throws InterruptedException
     *             If this thread is interrupted while waiting for the transfer
     *             to complete.
     */
	public void waitForCompletion()
            throws SCSClientException, SCSServiceException, InterruptedException {
        try {
            Object result = null;
            while (!monitor.isDone() || result == null) {
                Future<?> f = monitor.getFuture();
                result = f.get();
            }
        } catch (ExecutionException e) {
            rethrowExecutionException(e);
            
        }
    }

    /**
     * Waits for this transfer to finish and returns any error that occurred, or
     * returns <code>null</code> if no errors occurred.
     * This is a blocking call; the current thread
     * will be suspended until this transfer either fails or completes
     * successfully.
     *
     * @return Any error that occurred while processing this transfer.
     *         Otherwise returns <code>null</code> if no errors occurred.
     *
     * @throws InterruptedException
     *             If this thread is interrupted while waiting for the transfer
     *             to complete.
     */
    public SCSClientException waitForException() throws InterruptedException {
        try {

            while (!monitor.isDone()) {
                monitor.getFuture().get();
            }
            monitor.getFuture().get();
            return null;
        } catch (ExecutionException e) {
            return unwrapExecutionException(e);
        }
    }

    /**
     * Returns a human-readable description of this transfer.
     *
     * @return A human-readable description of this transfer.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the current state of this transfer.
     *
     * @return The current state of this transfer.
     */
    public synchronized TransferState getState() {
        return state;
    }

    /**
     * Sets the current state of this transfer.
     */
    public void setState(TransferState state) {
        synchronized (this) {
            this.state = state;
        }
        for ( TransferStateChangeListener listener : stateChangeListeners ) {
            listener.transferStateChanged(this, state);
        }
    }
    
    /**
     * Notifies all the registered state change listeners of the state update.
     */
    public void notifyStateChangeListeners(TransferState state) {
        for ( TransferStateChangeListener listener : stateChangeListeners ) {
            listener.transferStateChanged(this, state);
        }
    }

    /**
     * Adds the specified progress listener to the list of listeners
     * receiving updates about this transfer's progress.
     *
     * @param listener
     *            The progress listener to add.
     */
    public synchronized void addProgressListener(ProgressListener listener) {
        progressListenerChain.addProgressListener(listener);
    }

    /**
     * Removes the specified progress listener from the list of progress
     * listeners receiving updates about this transfer's progress.
     *
     * @param listener
     *            The progress listener to remove.
     */
    public synchronized void removeProgressListener(ProgressListener listener) {
        progressListenerChain.removeProgressListener(listener);
    }
    
    /**
     * @deprecated Replaced by {@link #addProgressListener(ProgressListener)}
     */
    @Deprecated
    public synchronized void addProgressListener(com.sina.cloudstorage.services.scs.model.ProgressListener listener) {
        progressListenerChain.addProgressListener(new LegacyS3ProgressListener(listener));
    }
    
    /**
     * @deprecated Replaced by {@link #removeProgressListener(ProgressListener)}
     */
    @Deprecated
    public synchronized void removeProgressListener(com.sina.cloudstorage.services.scs.model.ProgressListener listener) {
        progressListenerChain.removeProgressListener(new LegacyS3ProgressListener(listener));
    }
    
    /**
     * Adds the given state change listener to the collection of listeners.
     */
    public synchronized void addStateChangeListener(TransferStateChangeListener listener) {
        if ( listener != null )
            stateChangeListeners.add(listener);
    }

    /**
     * Removes the given state change listener from the collection of listeners.
     */
    public synchronized void removeStateChangeListener(TransferStateChangeListener listener) {
        if ( listener != null )
            stateChangeListeners.remove(listener);
    }

    /**
     * Returns progress information about this transfer.
     *
     * @return The progress information about this transfer.
     */
    public TransferProgress getProgress() {
        return transferProgress;
    }

    /**
     * Sets the monitor used to poll for transfer completion.
     */
    public void setMonitor(TransferMonitor monitor) {
        this.monitor = monitor;
    }
    
    public TransferMonitor getMonitor() {
        return monitor;
    }
    
    protected void fireProgressEvent(final int eventType) {
        if (progressListenerChainCallbackExecutor == null) return;
        progressListenerChainCallbackExecutor.progressChanged(new ProgressEvent(eventType, 0));
    }
    
    /**
     * Examines the cause of the specified ExecutionException and either
     * rethrows it directly (if it's a type of SCSClientException) or wraps
     * it in an SCSClientException and rethrows it.
     *
     * @param e
     *            The execution exception to examine.
     */
    protected void rethrowExecutionException(ExecutionException e) {
        throw unwrapExecutionException(e);
    }

    /**
     * Unwraps the root exception that caused the specified ExecutionException
     * and returns it. If it was not an instance of SCSClientException, it is
     * wrapped as an SCSClientException.
     *
     * @param e
     *            The ExecutionException to unwrap.
     *
     * @return The root exception that caused the specified ExecutionException.
     */
    protected SCSClientException unwrapExecutionException(ExecutionException e) {
        Throwable t = e.getCause();
        if (t instanceof SCSClientException) return (SCSClientException)t;
        return new SCSClientException("Unable to complete transfer: " + t.getMessage(), t);
    }

}
