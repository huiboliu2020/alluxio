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

package alluxio.client.block;

import alluxio.AbstractMasterClient;
import alluxio.Constants;
import alluxio.client.block.options.GetWorkerReportOptions;
import alluxio.grpc.BlockMasterClientServiceGrpc;
import alluxio.grpc.DecommissionWorkerPOptions;
import alluxio.grpc.GetBlockInfoPRequest;
import alluxio.grpc.GetBlockMasterInfoPOptions;
import alluxio.grpc.GetCapacityBytesPOptions;
import alluxio.grpc.GetUsedBytesPOptions;
import alluxio.grpc.GetWorkerInfoListPOptions;
import alluxio.grpc.GetWorkerLostStoragePOptions;
import alluxio.grpc.GrpcUtils;
import alluxio.grpc.RemoveDecommissionedWorkerPOptions;
import alluxio.grpc.ServiceType;
import alluxio.grpc.WorkerLostStorageInfo;
import alluxio.master.MasterClientContext;
import alluxio.master.selectionpolicy.MasterSelectionPolicy;
import alluxio.retry.RetryPolicy;
import alluxio.wire.BlockInfo;
import alluxio.wire.BlockMasterInfo;
import alluxio.wire.BlockMasterInfo.BlockMasterInfoField;
import alluxio.wire.WorkerInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A wrapper for the gRPC client to interact with the block master, used by alluxio clients.
 */
@ThreadSafe
public final class RetryHandlingBlockMasterClient extends AbstractMasterClient
    implements BlockMasterClient {
  private static final Logger RPC_LOG = LoggerFactory.getLogger(BlockMasterClient.class);
  private BlockMasterClientServiceGrpc.BlockMasterClientServiceBlockingStub mClient = null;

  /**
   * Creates a new block master client.
   *
   * @param conf master client configuration
   */
  public RetryHandlingBlockMasterClient(MasterClientContext conf) {
    super(conf);
  }

  /**
   * Creates a new block master client.
   *
   * @param conf master client configuration
   * @param address the master address the client connects to
   */
  public RetryHandlingBlockMasterClient(MasterClientContext conf, InetSocketAddress address) {
    super(conf, MasterSelectionPolicy.Factory.specifiedMaster(address));
  }

  /**
   * Creates a new block master client.
   *
   * @param conf master client configuration
   * @param address the master address the client connects to
   * @param retryPolicy retry policy to use
   */
  public RetryHandlingBlockMasterClient(
      MasterClientContext conf, InetSocketAddress address,
      Supplier<RetryPolicy> retryPolicy) {
    super(conf, MasterSelectionPolicy.Factory.specifiedMaster(address), retryPolicy);
  }

  @Override
  protected ServiceType getRemoteServiceType() {
    return ServiceType.BLOCK_MASTER_CLIENT_SERVICE;
  }

  @Override
  protected String getServiceName() {
    return Constants.BLOCK_MASTER_CLIENT_SERVICE_NAME;
  }

  @Override
  protected long getServiceVersion() {
    return Constants.BLOCK_MASTER_CLIENT_SERVICE_VERSION;
  }

  @Override
  protected void afterConnect() {
    mClient = BlockMasterClientServiceGrpc.newBlockingStub(mChannel);
  }

  @Override
  public List<WorkerInfo> getWorkerInfoList() throws IOException {
    return retryRPC(() -> {
      List<WorkerInfo> result = new ArrayList<>();
      for (alluxio.grpc.WorkerInfo workerInfo : mClient
          .getWorkerInfoList(GetWorkerInfoListPOptions.getDefaultInstance())
          .getWorkerInfosList()) {
        result.add(GrpcUtils.fromProto(workerInfo));
      }
      return result;
    }, RPC_LOG, "GetWorkerInfoList", "");
  }

  @Override
  public void removeDecommissionedWorker(String workerName) throws IOException {
    retryRPC(() -> mClient.removeDecommissionedWorker(RemoveDecommissionedWorkerPOptions
                    .newBuilder().setWorkerName(workerName).build()),
            RPC_LOG, "RemoveDecommissionedWorker", "");
  }

  @Override
  public List<WorkerInfo> getWorkerReport(final GetWorkerReportOptions options)
      throws IOException {
    return retryRPC(() -> {
      List<WorkerInfo> result = new ArrayList<>();
      for (alluxio.grpc.WorkerInfo workerInfo : mClient.getWorkerReport(options.toProto())
          .getWorkerInfosList()) {
        result.add(GrpcUtils.fromProto(workerInfo));
      }
      return result;
    }, RPC_LOG, "GetWorkerReport", "options=%s", options);
  }

  @Override
  public List<WorkerLostStorageInfo> getWorkerLostStorage() throws IOException {
    return retryRPC(() -> mClient
        .getWorkerLostStorage(GetWorkerLostStoragePOptions.getDefaultInstance())
        .getWorkerLostStorageInfoList(),
        RPC_LOG, "GetWorkerLostStorage", "");
  }

  @Override
  public BlockInfo getBlockInfo(final long blockId) throws IOException {
    return retryRPC(() -> GrpcUtils.fromProto(
        mClient.getBlockInfo(GetBlockInfoPRequest.newBuilder().setBlockId(blockId).build())
            .getBlockInfo()), RPC_LOG, "GetBlockInfo", "blockId=%d", blockId);
  }

  @Override
  public BlockMasterInfo getBlockMasterInfo(final Set<BlockMasterInfoField> fields)
      throws IOException {
    return retryRPC(() -> BlockMasterInfo
        .fromProto(mClient.getBlockMasterInfo(GetBlockMasterInfoPOptions.newBuilder()
            .addAllFilters(
                fields.stream().map(BlockMasterInfoField::toProto).collect(Collectors.toList()))
            .build()).getBlockMasterInfo()), RPC_LOG, "GetBlockMasterInfo", "fields=%s", fields);
  }

  @Override
  public long getCapacityBytes() throws IOException {
    return retryRPC(() -> mClient
        .getCapacityBytes(GetCapacityBytesPOptions.getDefaultInstance()).getBytes(),
        RPC_LOG, "GetCapacityBytes", "");
  }

  @Override
  public long getUsedBytes() throws IOException {
    return retryRPC(
        () -> mClient.getUsedBytes(GetUsedBytesPOptions.getDefaultInstance()).getBytes(),
        RPC_LOG, "GetUsedBytes", "");
  }

  @Override
  public void decommissionWorker(DecommissionWorkerPOptions options) throws IOException {
    retryRPC(() -> mClient.decommissionWorker(options),
        RPC_LOG, "DecommissionWorker", "workerName=%s,options=%s",
        options.getWorkerName(), options);
  }
}
