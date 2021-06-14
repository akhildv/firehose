package io.odpf.firehose.sink.objectstorage.writer;

import io.odpf.firehose.sink.objectstorage.message.Record;
import io.odpf.firehose.sink.objectstorage.writer.local.LocalFileCheckerWorker;
import io.odpf.firehose.sink.objectstorage.writer.local.LocalFileWriter;
import io.odpf.firehose.sink.objectstorage.writer.local.LocalFileWriterWrapper;
import io.odpf.firehose.sink.objectstorage.writer.remote.ObjectStorageFileCheckerWorker;
import io.odpf.firehose.sink.objectstorage.writer.remote.ObjectStorageWriterConfig;
import io.odpf.firehose.sink.objectstorage.writer.remote.ObjectStorageWriterWorkerFuture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class WriterOrchestrator implements Closeable {
    private static final int FILE_CHECKER_THREAD_INITIAL_DELAY_SECONDS = 10;
    private static final int FILE_CHECKER_THREAD_FREQUENCY_SECONDS = 5;
    private final Map<String, LocalFileWriter> timePartitionWriterMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService localFileCheckerScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService remoteFileCheckerScheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService remoteUploadScheduler = Executors.newFixedThreadPool(10);
    private final BlockingQueue<String> flushedToRemotePaths = new LinkedBlockingQueue<>();
    private final LocalFileWriterWrapper localFileWriterWrapper;
    private final WriterOrchestratorStatus writerOrchestratorStatus;

    public WriterOrchestrator(LocalFileWriterWrapper localFileWriterWrapper,
                              ObjectStorageWriterConfig objectStorageWriterConfig) {

        this.localFileWriterWrapper = localFileWriterWrapper;

        BlockingQueue<String> toBeFlushedToRemotePaths = new LinkedBlockingQueue<>();
        Set<ObjectStorageWriterWorkerFuture> remoteUploadFutures = new HashSet<>();

        ScheduledFuture<?> localWriterFuture = localFileCheckerScheduler.scheduleAtFixedRate(
                new LocalFileCheckerWorker(
                        toBeFlushedToRemotePaths,
                        timePartitionWriterMap,
                        localFileWriterWrapper.getPolicies()),
                FILE_CHECKER_THREAD_INITIAL_DELAY_SECONDS,
                FILE_CHECKER_THREAD_FREQUENCY_SECONDS,
                TimeUnit.SECONDS);

        ScheduledFuture<?> remoteWriterFuture = remoteFileCheckerScheduler.scheduleWithFixedDelay(
                new ObjectStorageFileCheckerWorker(
                        toBeFlushedToRemotePaths,
                        flushedToRemotePaths,
                        remoteUploadFutures,
                        remoteUploadScheduler,
                        objectStorageWriterConfig),
                FILE_CHECKER_THREAD_INITIAL_DELAY_SECONDS,
                FILE_CHECKER_THREAD_FREQUENCY_SECONDS,
                TimeUnit.SECONDS);

        writerOrchestratorStatus = new WriterOrchestratorStatus(false, localWriterFuture, remoteWriterFuture, null);
        checkForLocalFileWriterCompletion();
        checkForRemoteFileWriterCompletion();
    }

    private void checkForLocalFileWriterCompletion() {
        new Thread(() -> {
            try {
                writerOrchestratorStatus.getLocalFileWriterFuture().get();
            } catch (InterruptedException e) {
                writerOrchestratorStatus.setThrowable(e);
            } catch (ExecutionException e) {
                writerOrchestratorStatus.setThrowable(e.getCause());
            } finally {
                writerOrchestratorStatus.setClosed(true);
            }
        }).start();
    }

    private void checkForRemoteFileWriterCompletion() {
        new Thread(() -> {
            try {
                writerOrchestratorStatus.getRemoteFileWriterFuture().get();
            } catch (InterruptedException e) {
                writerOrchestratorStatus.setThrowable(e);
            } catch (ExecutionException e) {
                writerOrchestratorStatus.setThrowable(e.getCause());
            } finally {
                writerOrchestratorStatus.setClosed(true);
            }
        }).start();
    }

    /**
     * @return Return all paths which are flushed to remote and drain the list
     */
    public Set<String> getFlushedPaths() {
        Set<String> flushedPath = new HashSet<>();
        flushedToRemotePaths.drainTo(flushedPath);
        return flushedPath;
    }

    public String write(Record record) throws IOException {
        if (writerOrchestratorStatus.isClosed()) {
            throw new IOException(writerOrchestratorStatus.getThrowable());
        }
        Path partitionedPath = localFileWriterWrapper.getTimePartitionPath().create(record);
        synchronized (timePartitionWriterMap) {
            if (!timePartitionWriterMap.containsKey(partitionedPath.toString())) {
                timePartitionWriterMap.put(partitionedPath.toString(), this.localFileWriterWrapper.createLocalFileWriter(partitionedPath));
            }
            timePartitionWriterMap.get(partitionedPath.toString()).write(record);
            return timePartitionWriterMap.get(partitionedPath.toString()).getFullPath();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (timePartitionWriterMap) {
            for (LocalFileWriter writer : timePartitionWriterMap.values()) {
                writer.close();
            }
        }
        localFileCheckerScheduler.shutdown();
        remoteFileCheckerScheduler.shutdown();
        remoteUploadScheduler.shutdown();
        writerOrchestratorStatus.setClosed(true);
    }
}
