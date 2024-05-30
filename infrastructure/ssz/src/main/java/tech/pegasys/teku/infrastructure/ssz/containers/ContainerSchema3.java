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

package tech.pegasys.teku.infrastructure.ssz.containers;

import java.util.List;
import java.util.function.BiFunction;
import tech.pegasys.teku.infrastructure.ssz.SszContainer;
import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

/** Autogenerated by tech.pegasys.teku.ssz.backing.ContainersGenerator */
public abstract class ContainerSchema3<
        C extends SszContainer, V0 extends SszData, V1 extends SszData, V2 extends SszData>
    extends AbstractSszContainerSchema<C> {

  public static <C extends SszContainer, V0 extends SszData, V1 extends SszData, V2 extends SszData>
      ContainerSchema3<C, V0, V1, V2> create(
          final SszSchema<V0> fieldSchema0,
          final SszSchema<V1> fieldSchema1,
          final SszSchema<V2> fieldSchema2,
          final BiFunction<ContainerSchema3<C, V0, V1, V2>, TreeNode, C> instanceCtor) {
    return new ContainerSchema3<>(fieldSchema0, fieldSchema1, fieldSchema2) {
      @Override
      public C createFromBackingNode(final TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }

  protected ContainerSchema3(
      final SszSchema<V0> fieldSchema0,
      final SszSchema<V1> fieldSchema1,
      final SszSchema<V2> fieldSchema2) {

    super(List.of(fieldSchema0, fieldSchema1, fieldSchema2));
  }

  protected ContainerSchema3(
      final String containerName,
      final NamedSchema<V0> fieldNamedSchema0,
      final NamedSchema<V1> fieldNamedSchema1,
      final NamedSchema<V2> fieldNamedSchema2) {

    super(containerName, List.of(fieldNamedSchema0, fieldNamedSchema1, fieldNamedSchema2));
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V0> getFieldSchema0() {
    return (SszSchema<V0>) getChildSchema(0);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V1> getFieldSchema1() {
    return (SszSchema<V1>) getChildSchema(1);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V2> getFieldSchema2() {
    return (SszSchema<V2>) getChildSchema(2);
  }
}
