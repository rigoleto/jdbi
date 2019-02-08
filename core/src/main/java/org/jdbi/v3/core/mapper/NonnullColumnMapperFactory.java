package org.jdbi.v3.core.mapper;

import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.qualifier.QualifiedType;

public class NonnullColumnMapperFactory implements QualifiedColumnMapperFactory {
    @Override
    public Optional<ColumnMapper<?>> build(QualifiedType<?> type, ConfigRegistry config) {
        if (type.hasQualifier(Nonnull.class)) {
            return lookup(type, config);
        } else {
            return Optional.empty();
        }
    }

    private static <T> Optional<ColumnMapper<?>> lookup(QualifiedType<T> type, ConfigRegistry config) {
        Set<Annotation> allExceptNonnull = type.getQualifiers().stream()
            .filter(x -> !(x instanceof Nonnull))
            .collect(Collectors.toSet());

        return config.get(ColumnMappers.class).findFor(type.with(allExceptNonnull))
            .map(mapper -> (r, i, ctx) -> Objects.requireNonNull(mapper.map(r, i, ctx), "type annotated with @Nonnull got a null value"));
    }
}
