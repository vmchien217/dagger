/*
 * Copyright (C) 2019 The Dagger Authors.
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

package dagger.internal.codegen.kotlin;

import static com.google.common.base.Preconditions.checkArgument;
import static dagger.internal.codegen.base.MoreAnnotationValues.getIntArrayValue;
import static dagger.internal.codegen.base.MoreAnnotationValues.getIntValue;
import static dagger.internal.codegen.base.MoreAnnotationValues.getOptionalIntValue;
import static dagger.internal.codegen.base.MoreAnnotationValues.getOptionalStringValue;
import static dagger.internal.codegen.base.MoreAnnotationValues.getStringArrayValue;
import static dagger.internal.codegen.base.MoreAnnotationValues.getStringValue;
import static dagger.internal.codegen.langmodel.DaggerElements.getAnnotationMirror;
import static dagger.internal.codegen.langmodel.DaggerElements.getFieldDescriptor;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import dagger.internal.codegen.extension.DaggerCollectors;
import dagger.internal.codegen.langmodel.DaggerElements;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import kotlin.Metadata;
import kotlinx.metadata.Flag;
import kotlinx.metadata.Flag.ValueParameter;
import kotlinx.metadata.KmClassVisitor;
import kotlinx.metadata.KmConstructorVisitor;
import kotlinx.metadata.KmExtensionType;
import kotlinx.metadata.KmPropertyExtensionVisitor;
import kotlinx.metadata.KmPropertyVisitor;
import kotlinx.metadata.KmValueParameterVisitor;
import kotlinx.metadata.jvm.JvmFieldSignature;
import kotlinx.metadata.jvm.JvmMethodSignature;
import kotlinx.metadata.jvm.JvmPropertyExtensionVisitor;
import kotlinx.metadata.jvm.KotlinClassHeader;
import kotlinx.metadata.jvm.KotlinClassMetadata;

/** Data class of a TypeElement and its Kotlin metadata. */
final class KotlinMetadata {

  private final TypeElement typeElement;

  private final KotlinClassMetadata.Class data;

  /**
   * Kotlin metadata flag for this class.
   *
   * <p>Use {@link Flag.Class} to apply the right mask and obtain a specific value.
   */
  private final Supplier<Integer> flags;

  private final Supplier<Optional<String>> companionObjectName;

  private final Supplier<List<Property>> properties;

  private final Supplier<Map<String, Property>> propertyDescriptors;

  private final Supplier<Map<String, ExecutableElement>> methodDescriptors;

  // Map that associates field elements with its Kotlin synthetic method for annotations.
  private final Supplier<Map<VariableElement, Optional<MethodForAnnotations>>>
      elementFieldAnnotationMethodMap;

  // Map that associates field elements with its Kotlin getter method.
  private final Supplier<Map<VariableElement, Optional<ExecutableElement>>>
      elementFieldGetterMethodMap;

  private final Supplier<Boolean> containsConstructorWithDefaultParam;

  private KotlinMetadata(TypeElement typeElement, KotlinClassMetadata.Class data) {
    this.typeElement = typeElement;
    this.data = data;
    this.flags = Suppliers.memoize(this::retrieveFlags);
    this.companionObjectName = Suppliers.memoize(this::retrieveCompanionObjectName);
    this.properties = Suppliers.memoize(this::retrieveProperties);
    this.propertyDescriptors =
        Suppliers.memoize(
            () ->
                properties.get().stream()
                    .filter(property -> property.getFieldSignature().isPresent())
                    .collect(
                        Collectors.toMap(
                            property -> property.getFieldSignature().get(), Function.identity())));
    this.methodDescriptors =
        Suppliers.memoize(
            () ->
                ElementFilter.methodsIn(typeElement.getEnclosedElements()).stream()
                    .collect(
                        Collectors.toMap(
                            DaggerElements::getMethodDescriptor, Function.identity())));
    this.elementFieldAnnotationMethodMap =
        Suppliers.memoize(
            () -> mapFieldToAnnotationMethod(propertyDescriptors.get(), methodDescriptors.get()));
    this.elementFieldGetterMethodMap =
        Suppliers.memoize(
            () -> mapFieldToGetterMethod(propertyDescriptors.get(), methodDescriptors.get()));
    this.containsConstructorWithDefaultParam =
        Suppliers.memoize(this::checkConstructorForDefaultParam);
  }

  private int retrieveFlags() {
    FlagsVisitor visitor = new FlagsVisitor();
    data.accept(visitor);
    return visitor.flags;
  }

  private static final class FlagsVisitor extends KmClassVisitor {

    int flags;

    @Override
    public void visit(int flags, String name) {
      this.flags = flags;
    }
  }

  private Optional<String> retrieveCompanionObjectName() {
    CompanionObjectNameVisitor visitor = new CompanionObjectNameVisitor();
    data.accept(visitor);
    return visitor.name;
  }

  private static final class CompanionObjectNameVisitor extends KmClassVisitor {

    Optional<String> name = Optional.empty();

    @Override
    public void visitCompanionObject(String name) {
      this.name = Optional.of(name);
    }
  }

  private List<Property> retrieveProperties() {
    PropertiesVisitor visitor = new PropertiesVisitor();
    data.accept(visitor);
    return visitor.properties;
  }

  private static final class PropertiesVisitor extends KmClassVisitor {

    List<Property> properties = new ArrayList<>();

    @Override
    public KmPropertyVisitor visitProperty(
        int flags, String name, int getterFlags, int setterFlags) {
      return new KmPropertyVisitor() {
        Optional<String> fieldSignature;
        Optional<String> getterSignature = Optional.empty();
        Optional<String> methodForAnnotationsSignature = Optional.empty();

        @Override
        public KmPropertyExtensionVisitor visitExtensions(KmExtensionType kmExtensionType) {
          if (!kmExtensionType.equals(JvmPropertyExtensionVisitor.TYPE)) {
            return null;
          }

          return new JvmPropertyExtensionVisitor() {
            @Override
            public void visit(
                int jvmFlags,
                @Nullable JvmFieldSignature jvmFieldSignature,
                @Nullable JvmMethodSignature jvmGetterSignature,
                @Nullable JvmMethodSignature jvmSetterSignature) {
              fieldSignature =
                  Optional.ofNullable(jvmFieldSignature).map(JvmFieldSignature::asString);
              getterSignature =
                  Optional.ofNullable(jvmGetterSignature).map(JvmMethodSignature::asString);
            }

            @Override
            public void visitSyntheticMethodForAnnotations(
                @Nullable JvmMethodSignature methodSignature) {
              methodForAnnotationsSignature =
                  Optional.ofNullable(methodSignature).map(JvmMethodSignature::asString);
            }
          };
        }

        @Override
        public void visitEnd() {
          properties.add(
              new Property(
                  name, flags, fieldSignature, getterSignature, methodForAnnotationsSignature));
        }
      };
    }
  }

  private Map<VariableElement, Optional<MethodForAnnotations>> mapFieldToAnnotationMethod(
      Map<String, Property> propertyDescriptors, Map<String, ExecutableElement> methodDescriptors) {
    return ElementFilter.fieldsIn(typeElement.getEnclosedElements()).stream()
        .filter(field -> !field.getModifiers().contains(Modifier.STATIC))
        .collect(
            Collectors.toMap(
                Function.identity(),
                field ->
                    findProperty(field, propertyDescriptors)
                        .getMethodForAnnotationsSignature()
                        .map(
                            signature ->
                                Optional.ofNullable(methodDescriptors.get(signature))
                                    .map(MethodForAnnotations::new)
                                    // The method may be missing across different compilations.
                                    // See https://youtrack.jetbrains.com/issue/KT-34684
                                    .orElse(MethodForAnnotations.MISSING))));
  }

  private Map<VariableElement, Optional<ExecutableElement>> mapFieldToGetterMethod(
      Map<String, Property> propertyDescriptors, Map<String, ExecutableElement> methodDescriptors) {
    return ElementFilter.fieldsIn(typeElement.getEnclosedElements()).stream()
        .filter(field -> !field.getModifiers().contains(Modifier.STATIC))
        .collect(
            Collectors.toMap(
                Function.identity(),
                field ->
                    findProperty(field, propertyDescriptors)
                        .getGetterMethodSignature()
                        .flatMap(
                            signature -> Optional.ofNullable(methodDescriptors.get(signature)))));
  }

  private Property findProperty(VariableElement field, Map<String, Property> propertyDescriptors) {
    String fieldDescriptor = getFieldDescriptor(field);
    if (propertyDescriptors.containsKey(fieldDescriptor)) {
      return propertyDescriptors.get(fieldDescriptor);
    } else {
      // Fallback to finding property by name, see: https://youtrack.jetbrains.com/issue/KT-35124
      return propertyDescriptors.values().stream()
          .filter(property -> field.getSimpleName().contentEquals(property.name))
          .collect(DaggerCollectors.onlyElement());
    }
  }

  private boolean checkConstructorForDefaultParam() {
    ConstructorWithDefaultParamVisitor visitor = new ConstructorWithDefaultParamVisitor();
    data.accept(visitor);
    return visitor.containsConstructorWithDefaultParam;
  }

  private static final class ConstructorWithDefaultParamVisitor extends KmClassVisitor {

    boolean containsConstructorWithDefaultParam;

    @Override
    public KmConstructorVisitor visitConstructor(int flags) {
      return new KmConstructorVisitor() {
        @Override
        public KmValueParameterVisitor visitValueParameter(int flags, String name) {
          containsConstructorWithDefaultParam |=
              ValueParameter.DECLARES_DEFAULT_VALUE.invoke(flags);
          return super.visitValueParameter(flags, name);
        }
      };
    }
  }

  TypeElement getTypeElement() {
    return typeElement;
  }

  /** Gets the synthetic method for annotations of a given field element. */
  Optional<ExecutableElement> getSyntheticAnnotationMethod(VariableElement fieldElement) {
    checkArgument(elementFieldAnnotationMethodMap.get().containsKey(fieldElement));
    return elementFieldAnnotationMethodMap
        .get()
        .get(fieldElement)
        .map(
            methodForAnnotations -> {
              if (methodForAnnotations == MethodForAnnotations.MISSING) {
                throw new IllegalStateException(
                    "Method for annotations is missing for " + fieldElement);
              }
              return methodForAnnotations.getMethod();
            });
  }

  /**
   * Returns true if the synthetic method for annotations is missing. This can occur when inspecting
   * the Kotlin metadata of a property from another compilation unit.
   */
  boolean isMissingSyntheticAnnotationMethod(VariableElement fieldElement) {
    checkArgument(elementFieldAnnotationMethodMap.get().containsKey(fieldElement));
    return elementFieldAnnotationMethodMap
        .get()
        .get(fieldElement)
        .map(methodForAnnotations -> methodForAnnotations == MethodForAnnotations.MISSING)
        // This can be missing if there was no property annotation at all (e.g. no annotations or
        // the qualifier is already properly attached to the field). For these cases, it isn't
        // considered missing since there was no method to look for in the first place.
        .orElse(false);
  }

  boolean isObjectClass() {
    return Flag.Class.IS_OBJECT.invoke(flags.get());
  }

  /** Returns true if the type element of this metadata is a Kotlin companion object. */
  boolean isCompanionObjectClass() {
    return Flag.Class.IS_COMPANION_OBJECT.invoke(flags.get());
  }

  /**
   * Returns the name of the companion object enclosed by the type element of this metadata. If the
   * type element this metadata belongs to does not have a companion object, then this method
   * returns an empty optional.
   */
  Optional<String> getCompanionObjectName() {
    return companionObjectName.get();
  }

  boolean isPrivate() {
    return Flag.IS_PRIVATE.invoke(flags.get());
  }

  /** Gets the getter method of a given field element corresponding to a property. */
  Optional<ExecutableElement> getPropertyGetter(VariableElement fieldElement) {
    checkArgument(elementFieldGetterMethodMap.get().containsKey(fieldElement));
    return elementFieldGetterMethodMap.get().get(fieldElement);
  }

  /** Returns true if any constructor of the defined a default parameter. */
  public boolean containsConstructorWithDefaultParam() {
    return containsConstructorWithDefaultParam.get();
  }

  /** Parse Kotlin class metadata from a given type element * */
  static KotlinMetadata from(TypeElement typeElement) {
    return new KotlinMetadata(typeElement, metadataOf(typeElement));
  }

  private static KotlinClassMetadata.Class metadataOf(TypeElement typeElement) {
    Optional<AnnotationMirror> metadataAnnotation =
        getAnnotationMirror(typeElement, Metadata.class);
    Preconditions.checkState(metadataAnnotation.isPresent());
    KotlinClassHeader header =
        new KotlinClassHeader(
            getIntValue(metadataAnnotation.get(), "k"),
            getIntArrayValue(metadataAnnotation.get(), "mv"),
            getIntArrayValue(metadataAnnotation.get(), "bv"),
            getStringArrayValue(metadataAnnotation.get(), "d1"),
            getStringArrayValue(metadataAnnotation.get(), "d2"),
            getStringValue(metadataAnnotation.get(), "xs"),
            getOptionalStringValue(metadataAnnotation.get(), "pn").orElse(null),
            getOptionalIntValue(metadataAnnotation.get(), "xi").orElse(null));
    KotlinClassMetadata metadata = KotlinClassMetadata.read(header);
    if (metadata == null) {
      // Should only happen on Kotlin < 1.0 (i.e. metadata version < 1.1)
      throw new IllegalStateException(
          "Unsupported metadata version. Check that your Kotlin version is >= 1.0");
    }
    if (metadata instanceof KotlinClassMetadata.Class) {
      // TODO(user): If when we need other types of metadata then move to right method.
      return (KotlinClassMetadata.Class) metadata;
    } else {
      throw new IllegalStateException("Unsupported metadata type: " + metadata);
    }
  }

  /* Data class representing Kotlin Property Metadata */
  private static final class Property {

    private final String name;
    private final int flags;
    private final Optional<String> fieldSignature;
    private final Optional<String> getterSignature;
    private final Optional<String> methodForAnnotationsSignature;

    Property(
        String name,
        int flags,
        Optional<String> fieldSignature,
        Optional<String> getterSignature,
        Optional<String> methodForAnnotationsSignature) {
      this.name = name;
      this.flags = flags;
      this.fieldSignature = fieldSignature;
      this.getterSignature = getterSignature;
      this.methodForAnnotationsSignature = methodForAnnotationsSignature;
    }

    /** Returns the simple name of this property. */
    String getName() {
      return name;
    }

    /**
     * Returns the Kotlin metadata flags for this property.
     *
     * <p>Use {@link Flag.Property} to apply the right mask and obtain a specific value.
     */
    int getFlags() {
      return flags;
    }

    /** Returns the JVM field descriptor of the backing field of this property. */
    Optional<String> getFieldSignature() {
      return fieldSignature;
    }

    Optional<String> getGetterMethodSignature() {
      return getterSignature;
    }

    /** Returns JVM method descriptor of the synthetic method for property annotations. */
    Optional<String> getMethodForAnnotationsSignature() {
      return methodForAnnotationsSignature;
    }
  }

  /* Data class that wraps the Kotlin property executable element for annotations */
  private static final class MethodForAnnotations {

    static final MethodForAnnotations MISSING = new MethodForAnnotations(null);

    private final ExecutableElement method;

    MethodForAnnotations(ExecutableElement method) {
      this.method = method;
    }

    public ExecutableElement getMethod() {
      return method;
    }
  }
}
