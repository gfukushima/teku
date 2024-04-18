/*
 * Copyright Consensys Software Inc., 2022
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.ethereum.executionclient.schema;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import tech.pegasys.teku.infrastructure.bytes.Bytes20;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.collections.impl.SszByteListImpl;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadBuilder;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadSchema;
import tech.pegasys.teku.spec.datastructures.execution.versions.capella.Withdrawal;

public class ExecutionPayloadV2 extends ExecutionPayloadV1 {
  public final List<WithdrawalV1> withdrawals;

  public ExecutionPayloadV2(
      final @JsonProperty("parentHash") Bytes32 parentHash,
      final @JsonProperty("feeRecipient") Bytes20 feeRecipient,
      final @JsonProperty("stateRoot") Bytes32 stateRoot,
      final @JsonProperty("receiptsRoot") Bytes32 receiptsRoot,
      final @JsonProperty("logsBloom") Bytes logsBloom,
      final @JsonProperty("prevRandao") Bytes32 prevRandao,
      final @JsonProperty("blockNumber") UInt64 blockNumber,
      final @JsonProperty("gasLimit") UInt64 gasLimit,
      final @JsonProperty("gasUsed") UInt64 gasUsed,
      final @JsonProperty("timestamp") UInt64 timestamp,
      final @JsonProperty("extraData") Bytes extraData,
      final @JsonProperty("baseFeePerGas") UInt256 baseFeePerGas,
      final @JsonProperty("blockHash") Bytes32 blockHash,
      final @JsonProperty("transactions") List<Bytes> transactions,
      final @JsonProperty("withdrawals") List<WithdrawalV1> withdrawals) {
    super(
        parentHash,
        feeRecipient,
        stateRoot,
        receiptsRoot,
        logsBloom,
        prevRandao,
        blockNumber,
        gasLimit,
        gasUsed,
        timestamp,
        extraData,
        baseFeePerGas,
        blockHash,
        transactions);
    this.withdrawals = withdrawals;
  }

  public static ExecutionPayloadV2 fromInternalExecutionPayload(
      final ExecutionPayload executionPayload) {
    List<WithdrawalV1> withdrawalsList = getWithdrawals(executionPayload.getOptionalWithdrawals());
    return new ExecutionPayloadV2(
        executionPayload.getParentHash(),
        executionPayload.getFeeRecipient(),
        executionPayload.getStateRoot(),
        executionPayload.getReceiptsRoot(),
        executionPayload.getLogsBloom(),
        executionPayload.getPrevRandao(),
        executionPayload.getBlockNumber(),
        executionPayload.getGasLimit(),
        executionPayload.getGasUsed(),
        executionPayload.getTimestamp(),
        executionPayload.getExtraData(),
        executionPayload.getBaseFeePerGas(),
        executionPayload.getBlockHash(),
        executionPayload.getTransactions().stream().map(SszByteListImpl::getBytes).toList(),
        withdrawalsList);
  }

  @Override
  protected ExecutionPayloadBuilder applyToBuilder(
      final ExecutionPayloadSchema<?> executionPayloadSchema,
      final ExecutionPayloadBuilder builder) {
    return super.applyToBuilder(executionPayloadSchema, builder)
        .withdrawals(
            () ->
                checkNotNull(withdrawals, "Withdrawals not provided when required").stream()
                    .map(
                        withdrawalV1 ->
                            createInternalWithdrawal(withdrawalV1, executionPayloadSchema))
                    .toList());
  }

  private Withdrawal createInternalWithdrawal(
      final WithdrawalV1 withdrawalV1, final ExecutionPayloadSchema<?> executionPayloadSchema) {
    return executionPayloadSchema
        .getWithdrawalSchemaRequired()
        .create(
            withdrawalV1.index,
            withdrawalV1.validatorIndex,
            withdrawalV1.address,
            withdrawalV1.amount);
  }

  public static List<WithdrawalV1> getWithdrawals(
      final Optional<SszList<Withdrawal>> maybeWithdrawals) {
    if (maybeWithdrawals.isEmpty()) {
      return List.of();
    }

    final List<WithdrawalV1> withdrawals = new ArrayList<>();

    for (Withdrawal w : maybeWithdrawals.get()) {
      withdrawals.add(
          new WithdrawalV1(w.getIndex(), w.getValidatorIndex(), w.getAddress(), w.getAmount()));
    }
    return withdrawals;
  }
}
