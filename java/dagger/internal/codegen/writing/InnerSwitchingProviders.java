/*
 * Copyright (C) 2018 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.writing;

import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static dagger.internal.codegen.binding.BindingRequest.bindingRequest;
import static dagger.model.RequestKind.INSTANCE;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.codegen.binding.ContributionBinding;
import dagger.internal.codegen.javapoet.Expression;
import dagger.internal.codegen.langmodel.DaggerTypes;
import dagger.internal.codegen.writing.SwitchingProviders.ProviderType;
import dagger.model.Key;
import javax.inject.Provider;
import javax.lang.model.type.TypeMirror;

/**
 * Generates {@linkplain BindingExpression binding expressions} for a binding that is represented by
 * an inner {@code SwitchingProvider} class.
 */
final class InnerSwitchingProviders {
  private final ComponentImplementation componentImplementation;
  private final ComponentBindingExpressions componentBindingExpressions;
  private final DaggerTypes types;
  private final InnerSwitchingProvider doubleCheckSwitchingProviders;
  private final InnerSwitchingProvider singleCheckSwitchingProviders;
  private final InnerSwitchingProvider unscopedSwitchingProviders;

  InnerSwitchingProviders(
      ComponentImplementation componentImplementation,
      ComponentBindingExpressions componentBindingExpressions,
      DaggerTypes types) {
    this.componentImplementation = componentImplementation;
    this.componentBindingExpressions = componentBindingExpressions;
    this.types = types;
    this.doubleCheckSwitchingProviders = new InnerSwitchingProvider(ProviderType.DOUBLE_CHECK);
    this.singleCheckSwitchingProviders = new InnerSwitchingProvider(ProviderType.SINGLE_CHECK);
    this.unscopedSwitchingProviders = new InnerSwitchingProvider(ProviderType.PROVIDER);
  }

  /**
   * Returns the binding expression for a binding that satisfies a {@link Provider} requests with a
   * inner {@code SwitchingProvider} class.
   */
  BindingExpression newBindingExpression(ContributionBinding binding) {
    return !binding.scope().isPresent()
        ? unscopedSwitchingProviders.newBindingExpression(binding)
        : binding.scope().get().isReusable()
            ? singleCheckSwitchingProviders.newBindingExpression(binding)
            : doubleCheckSwitchingProviders.newBindingExpression(binding);
  }

  private class InnerSwitchingProvider extends SwitchingProviders {
    private InnerSwitchingProvider(ProviderType providerType) {
      super(providerType, componentImplementation, types);
    }

    BindingExpression newBindingExpression(ContributionBinding binding) {
      return new BindingExpression() {
        @Override
        Expression getDependencyExpression(ClassName requestingClass) {
          return getProviderExpression(new SwitchCase(binding, requestingClass));
        }
      };
    }

    @Override
    protected TypeSpec createSwitchingProviderType(TypeSpec.Builder builder) {
      return builder
          .addModifiers(PRIVATE, FINAL)
          .addField(TypeName.INT, "id", PRIVATE, FINAL)
          .addMethod(
              constructorBuilder()
                  .addParameter(TypeName.INT, "id")
                  .addStatement("this.id = id")
                  .build())
          .build();
    }

    private final class SwitchCase implements SwitchingProviders.SwitchCase {
      private final ContributionBinding binding;
      private final ClassName requestingClass;

      SwitchCase(ContributionBinding binding, ClassName requestingClass) {
        this.binding = binding;
        this.requestingClass = requestingClass;
      }

      @Override
      public Key key() {
        return binding.key();
      }

      @Override
      public Expression getProviderExpression(ClassName switchingProviderClass, int switchId) {
        TypeMirror instanceType = types.accessibleType(binding.contributedType(), requestingClass);
        return Expression.create(
            types.wrapType(instanceType, Provider.class),
            CodeBlock.of("new $T<>($L)", switchingProviderClass, switchId));
      }

      @Override
      public Expression getReturnExpression(ClassName switchingProviderClass) {
        return componentBindingExpressions.getDependencyExpression(
            bindingRequest(binding.key(), INSTANCE), switchingProviderClass);
      }
    }
  }
}
