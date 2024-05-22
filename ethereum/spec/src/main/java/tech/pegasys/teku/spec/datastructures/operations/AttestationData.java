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

package tech.pegasys.teku.spec.datastructures.operations;

import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.containers.Container3;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema3;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;

public class AttestationData
    extends Container3<AttestationData, SszUInt64, SszBytes32, Checkpoint> {

  public static class AttestationDataSchema
      extends ContainerSchema3<AttestationData, SszUInt64, SszBytes32, Checkpoint> {

    public AttestationDataSchema() {
      super(
          "AttestationData",
          namedSchema("slot", SszPrimitiveSchemas.UINT64_SCHEMA),
          namedSchema("beacon_block_root", SszPrimitiveSchemas.BYTES32_SCHEMA),
          namedSchema("source", Checkpoint.SSZ_SCHEMA));
    }

    @Override
    public AttestationData createFromBackingNode(final TreeNode node) {
      return new AttestationData(this, node);
    }
  }

  public static final AttestationDataSchema SSZ_SCHEMA = new AttestationDataSchema();

  private AttestationData(final AttestationDataSchema type, final TreeNode backingNode) {
    super(type, backingNode);
  }

  public AttestationData(
      final UInt64 slot, final Bytes32 beaconBlockRoot, final Checkpoint source) {
    super(SSZ_SCHEMA, SszUInt64.of(slot), SszBytes32.of(beaconBlockRoot), source);
  }

  public AttestationData(final UInt64 slot, final AttestationData data) {
    this(slot, data.getBeaconBlockRoot(), data.getSource());
  }

  // Removed getTarget() method and related logic as it's no longer part of the Container due to
  // EIP-7549 changes
  // Removed getEarliestSlotForForkChoice() methods as they relied on the removed getTarget() method

  public UInt64 getSlot() {
    return getField0().get();
  }

  public Bytes32 getBeaconBlockRoot() {
    return getField1().get();
  }

  public Checkpoint getSource() {
    return getField2();
  }

  @Override
  public AttestationDataSchema getSchema() {
    return (AttestationDataSchema) super.getSchema();
  }
}
