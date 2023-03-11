/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.raft;

import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.grpc.DownloadSnapshotPRequest;
import alluxio.grpc.DownloadSnapshotPResponse;
import alluxio.grpc.LatestSnapshotInfoPRequest;
import alluxio.grpc.RaftJournalServiceGrpc;
import alluxio.grpc.SnapshotData;
import alluxio.grpc.SnapshotMetadata;
import alluxio.grpc.UploadSnapshotPRequest;
import alluxio.grpc.UploadSnapshotPResponse;
import alluxio.util.TarUtils;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * RPC handler for raft journal service.
 */
public class RaftJournalServiceHandler extends RaftJournalServiceGrpc.RaftJournalServiceImplBase {
  private static final Logger LOG =
      LoggerFactory.getLogger(RaftJournalServiceHandler.class);
  private final int mSnapshotReplicationChunkSize = (int) Configuration.getBytes(
      PropertyKey.MASTER_EMBEDDED_JOURNAL_SNAPSHOT_REPLICATION_CHUNK_SIZE);
  private final int mSnapshotCompressionLevel =
      Configuration.getInt(PropertyKey.MASTER_METASTORE_ROCKS_CHECKPOINT_COMPRESSION_LEVEL);
  private final SnapshotReplicationManager mManager;
  private final StateMachineStorage mStateMachineStorage;

  /**
   * @param manager the snapshot replication manager
   * @param storage the storage that the state machine uses for its snapshots
   */
  public RaftJournalServiceHandler(SnapshotReplicationManager manager,
                                   StateMachineStorage storage) {
    mManager = manager;
    mStateMachineStorage = storage;
  }

  @Override
  public void requestLatestSnapshotInfo(LatestSnapshotInfoPRequest request,
                                        StreamObserver<SnapshotMetadata> responseObserver) {
    LOG.debug("Received request for latest snapshot info");
    if (Context.current().isCancelled()) {
      responseObserver.onError(
          Status.CANCELLED.withDescription("Cancelled by client").asRuntimeException());
      return;
    }
    SnapshotInfo snapshot = mStateMachineStorage.getLatestSnapshot();
    SnapshotMetadata.Builder metadata = SnapshotMetadata.newBuilder();
    if (snapshot == null) {
      LOG.debug("No snapshot to send");
      metadata.setExists(false);
    } else {
      LOG.debug("Found snapshot {}", snapshot.getTermIndex());
      metadata.setExists(true)
          .setSnapshotTerm(snapshot.getTerm())
          .setSnapshotIndex(snapshot.getIndex());
    }
    responseObserver.onNext(metadata.build());
    responseObserver.onCompleted();
  }

  @Override
  public void requestLatestSnapshotData(SnapshotMetadata request,
                                     StreamObserver<SnapshotData> responseObserver) {
    if (Context.current().isCancelled()) {
      responseObserver.onError(
          Status.CANCELLED.withDescription("Cancelled by client").asRuntimeException());
      return;
    }
    TermIndex index = TermIndex.valueOf(request.getSnapshotTerm(), request.getSnapshotIndex());
    String snapshotDirName = SimpleStateMachineStorage
        .getSnapshotFileName(request.getSnapshotTerm(), request.getSnapshotIndex());
    Path snapshotPath = new File(mStateMachineStorage.getSnapshotDir(), snapshotDirName).toPath();

    byte[] buffer = new byte[mSnapshotReplicationChunkSize];
    try (OutputStream snapshotOutStream = new OutputStream() {
      private long mTotalBytesSent = 0L;
      private int mBufferIndex = 0;

      @Override
      public void write(int b) {
        buffer[mBufferIndex] = (byte) b;
        mBufferIndex++;
        if (mBufferIndex == buffer.length) {
          flushBuffer();
        }
      }

      @Override
      public void close() {
        if (mBufferIndex > 0) {
          flushBuffer();
        }
        responseObserver.onCompleted();
        LOG.debug("Total bytes sent: {}", mTotalBytesSent);
        LOG.info("Uploaded snapshot {} to leader", index);
      }

      private void flushBuffer() {
        ByteString bytes = ByteString.copyFrom(buffer, 0, mBufferIndex);
        LOG.debug("Sending chunk of size {}: {}", mBufferIndex, bytes.toByteArray());
        responseObserver.onNext(SnapshotData.newBuilder()
            .setChunk(bytes)
            .build());
        mTotalBytesSent += mBufferIndex;
        mBufferIndex = 0;
      }
    }) {
      LOG.debug("Begin snapshot upload of {}", index);
      TarUtils.writeTarGz(snapshotPath, snapshotOutStream, mSnapshotCompressionLevel);
    } catch (Exception e) {
      LOG.debug("Failed to upload snapshot {}", index);
      responseObserver.onError(e);
      responseObserver.onCompleted();
    }
  }

  @Override
  public StreamObserver<UploadSnapshotPRequest> uploadSnapshot(
      StreamObserver<UploadSnapshotPResponse> responseObserver) {
    return mManager.receiveSnapshotFromFollower(responseObserver);
  }

  @Override
  public StreamObserver<DownloadSnapshotPRequest> downloadSnapshot(
      StreamObserver<DownloadSnapshotPResponse> responseObserver) {
    return mManager.sendSnapshotToFollower(responseObserver);
  }
}
