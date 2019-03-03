/*
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
package org.jdbi.v3.core.mapper.reflect.internal;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.config.JdbiCache;
import org.jdbi.v3.core.generic.GenericTypes;
import org.jdbi.v3.core.internal.exceptions.Unchecked;
import org.jdbi.v3.core.mapper.reflect.internal.PojoProperties.PojoProperty;
import org.jdbi.v3.core.qualifier.QualifiedType;
import org.jdbi.v3.core.qualifier.Qualifiers;

public interface ImmutablesPropertiesFactory {
    PojoProperties<?> create(Type type, ConfigRegistry config);

    static <T, B> ImmutablesPropertiesFactory immutable(Class<T> defn, Supplier<B> builder) {
        return (t, config) -> new ImmutablePojoProperties<>(t, config, defn, builder);
    }

    static <T, M extends T> ImmutablesPropertiesFactory modifiable(Class<T> defn, Class<M> impl, Supplier<M> constructor) {
        return (t, config) -> new ModifiablePojoProperties<>(t, config, defn, impl, constructor);
    }

    static MethodHandle alwaysSet() {
        return MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, Object.class);
    }

    abstract class BasePojoProperties<T, B> extends PojoProperties<T> {
        protected final ConfigRegistry config;
        protected final Class<T> defn;
        protected final Class<?> impl;
        protected final Supplier<?> builder;

        BasePojoProperties(Type type, ConfigRegistry config, Class<T> defn, Class<?> impl, Supplier<B> builder) {
            super(type);
            this.config = config;
            this.defn = defn;
            this.impl = impl;
            this.builder = builder;
        }

        private static String propertyName(Method m) {
            final String[] prefixes = new String[] {"get", "is"};
            final String name = m.getName();
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    return chopPrefix(name, prefix.length());
                }
            }
            return name;
        }

        private static String chopPrefix(final String name, int off) {
            return name.substring(off, off + 1).toLowerCase() + name.substring(off + 1);
        }

        private static boolean isProperty(Method m) {
            return m.getParameterCount() == 0
                && !m.isSynthetic()
                && !Modifier.isStatic(m.getModifiers())
                && m.getDeclaringClass() != Object.class;
        }

        @Override
        public Map<String, ImmutablesPojoProperty<T>> getProperties() {
            return getPropertyMapCache().getCache()
                    .computeIfAbsent(getType(), x ->
                        Arrays.stream(defn.getMethods())
                            .filter(BasePojoProperties::isProperty)
                            .map(p -> createProperty(propertyName(p), p))
                            .collect(Collectors.toMap(PojoProperty::getName, Function.identity())));
        }

        abstract ImmutablesPojoProperty<T> createProperty(String name, Method m);
        abstract <C extends JdbiCache<C, Type, Map<String, ImmutablesPojoProperty<T>>>> C getPropertyMapCache();
    }

    class ImmutablePojoProperties<T, B> extends BasePojoProperties<T, B> {
        private MethodHandle builderBuild;

        ImmutablePojoProperties(Type type, ConfigRegistry config, Class<T> defn, Supplier<B> builder) {
            super(type, config, defn, null, builder);
            try {
                builderBuild = MethodHandles.lookup().unreflect(builder.get().getClass().getMethod("build"));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to inspect Immutables " + defn, e);
            }
        }
        @Override
        protected ImmutablesPojoProperty<T> createProperty(String name, Method m) {
            final Class<?> builderClass = builder.get().getClass();
            try {
                final Type propertyType = GenericTypes.resolveType(m.getGenericReturnType(), getType());
                return new ImmutablesPojoProperty<T>(
                        name,
                        QualifiedType.of(propertyType).withAnnotations(config.get(Qualifiers.class).qualifiers(m)),
                        m,
                        alwaysSet(),
                        MethodHandles.lookup().unreflect(m).asFixedArity(),
                        findBuilderSetter(builderClass, name, propertyType).asFixedArity());
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to inspect method " + m, e);
            }
        }

        private MethodHandle findBuilderSetter(final Class<?> builderClass, String name, Type type)
        throws IllegalAccessException, NoSuchMethodException {
            final List<NoSuchMethodException> failures = new ArrayList<>();
            final String setName = "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
            final Set<String> names = new HashSet<>(Arrays.asList(setName, name));
            for (String tryName : names) {
                try {
                    return MethodHandles.lookup().unreflect(builderClass.getMethod(tryName, GenericTypes.getErasedType(type)));
                } catch (NoSuchMethodException e) {
                    failures.add(e);
                }
            }
            for (Method m : builderClass.getMethods()) {
                if (names.contains(m.getName()) && m.getParameterCount() == 1) {
                    return MethodHandles.lookup().unreflect(m);
                }
            }
            final IllegalArgumentException iae = new IllegalArgumentException("Failed to find builder setter for property " + name + " of " + type);
            failures.forEach(iae::addSuppressed);
            throw iae;
        }

        @Override
        public PojoBuilder<T> create() {
            final Object b = builder.get();
            return new PojoBuilder<T>() {
                @Override
                public void set(String property, Object value) {
                    Unchecked.biFunction(getProperties().get(property).setter::invoke).apply(b, value);
                }

                @Override
                public T build() {
                    return defn.cast(Unchecked.function(builderBuild::invoke).apply(b));
                }
            };
        }

        @SuppressWarnings("unchecked")
        @Override
        ImmutablePropertiesCache getPropertyMapCache() {
            return config.get(ImmutablePropertiesCache.class);
        }
    }

    class ModifiablePojoProperties<T, M extends T> extends BasePojoProperties<T, M> {
        ModifiablePojoProperties(Type type, ConfigRegistry config, Class<T> defn, Class<M> impl, Supplier<M> constructor) {
            super(type, config, defn, impl, constructor);
        }

        @Override
        protected ImmutablesPojoProperty<T> createProperty(String name, Method m) {
            final Type propertyType = GenericTypes.resolveType(m.getGenericReturnType(), getType());
            try {
                return new ImmutablesPojoProperty<T>(
                        name,
                        QualifiedType.of(propertyType).withAnnotations(config.get(Qualifiers.class).qualifiers(m)),
                        m,
                        isSetMethod(name),
                        MethodHandles.lookup().unreflect(m).asFixedArity(),
                        MethodHandles.lookup().findVirtual(impl, setterName(name), MethodType.methodType(impl, GenericTypes.getErasedType(propertyType))).asFixedArity());
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to inspect method " + m, e);
            }
        }

        private MethodHandle isSetMethod(String name) {
            try {
                return MethodHandles.lookup().findVirtual(impl, name + "IsSet", MethodType.methodType(boolean.class));
            } catch (NoSuchMethodException e) {
                // not optional field
                return alwaysSet();
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to find IsSet method for " + name, e);
            }
        }

        private String setterName(String name) {
            return "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        @Override
        public PojoBuilder<T> create() {
            final Object instance = builder.get();
            return new PojoBuilder<T>() {
                @Override
                public void set(String property, Object value) {
                    Unchecked.biFunction(getProperties().get(property).setter::invoke).apply(instance, value);
                }

                @SuppressWarnings("unchecked")
                @Override
                public T build() {
                    return (T) instance;
                }
            };
        }

        @SuppressWarnings("unchecked")
        @Override
        ModifiablePropertiesCache getPropertyMapCache() {
            return config.get(ModifiablePropertiesCache.class);
        }
    }

    class ImmutablesPojoProperty<T> implements PojoProperty<T> {
        private final String name;
        private final QualifiedType<?> type;
        private final Method defn;
        private final MethodHandle isSet;
        private final MethodHandle getter;
        final MethodHandle setter;

        ImmutablesPojoProperty(String name, QualifiedType<?> type, Method defn, MethodHandle isSet, MethodHandle getter, MethodHandle setter) {
            this.name = name;
            this.type = type;
            this.defn = defn;
            this.isSet = isSet;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public QualifiedType<?> getQualifiedType() {
            return type;
        }

        @Override
        public <A extends Annotation> Optional<A> getAnnotation(Class<A> anno) {
            return Optional.ofNullable(defn.getAnnotation(anno));
        }

        @Override
        public Object get(T pojo) {
            return Unchecked.callable(() -> {
                if (Boolean.TRUE.equals(isSet.invoke(pojo))) {
                    return getter.invoke(pojo);
                } else {
                    return null;
                }
            }).call();
        }
    }

    class ImmutablePropertiesCache extends JdbiCache<ImmutablePropertiesCache, Type, Map<String, ImmutablesPojoProperty<?>>> {}
    class ModifiablePropertiesCache extends JdbiCache<ModifiablePropertiesCache, Type, Map<String, ImmutablesPojoProperty<?>>> {}
}
