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
public abstract class /*$$TypeClassName*/ ContainerTemplate /*$$*/<
        C extends SszContainer, /*$$ViewTypes*/ V0 extends SszData, V1 extends SszData /*$$*/>
    extends AbstractSszContainerSchema<C> {

  public static <
          C extends SszContainer, /*$$ViewTypes*/ V0 extends SszData, V1 extends SszData /*$$*/>
      /*$$TypeClassName*/ ContainerSchemaTemplate /*$$*/<C, /*$$ViewTypeNames*/ V0, V1 /*$$*/>
          create(
              /*$$FieldsDeclarations*/
              SszSchema<V0> fieldSchema1,
              SszSchema<V1> fieldSchema2 /*$$*/,
              BiFunction<
                      /*$$TypeClassName*/ ContainerSchemaTemplate /*$$*/<
                          C, /*$$ViewTypeNames*/ V0, V1 /*$$*/>,
                      TreeNode,
                      C>
                  instanceCtor) {
    return new /*$$TypeClassName*/ ContainerSchemaTemplate /*$$*/<>(
        /*$$Fields*/ fieldSchema1, fieldSchema2 /*$$*/) {
      @Override
      public C createFromBackingNode(TreeNode node) {
        return instanceCtor.apply(this, node);
      }
    };
  }

  protected /*$$TypeClassName*/ ContainerSchemaTemplate /*$$*/(
      /*$$FieldsDeclarations*/ SszSchema<V0> fieldSchema1, SszSchema<V1> fieldSchema2 /*$$*/) {

    super(List.of(/*$$Fields*/ fieldSchema1, fieldSchema2 /*$$*/));
  }

  protected /*$$TypeClassName*/ ContainerSchemaTemplate /*$$*/(
      String containerName,
      /*$$NamedFieldsDeclarations*/ NamedSchema<V0> fieldNamedSchema0,
      NamedSchema<V1> fieldNamedSchema1 /*$$*/) {

    super(containerName, List.of(/*$$NamedFields*/ fieldNamedSchema0, fieldNamedSchema1 /*$$*/));
  }

  /*$$TypeGetters*/
  @SuppressWarnings("unchecked")
  public SszSchema<V0> getFieldSchema0() {
    return (SszSchema<V0>) getChildSchema(0);
  }

  @SuppressWarnings("unchecked")
  public SszSchema<V1> getFieldSchema1() {
    return (SszSchema<V1>) getChildSchema(1);
  }
  /*$$*/
}
