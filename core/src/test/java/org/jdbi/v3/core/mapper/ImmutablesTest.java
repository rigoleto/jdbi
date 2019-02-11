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
package org.jdbi.v3.core.mapper;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.immutables.value.Value;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.annotation.JdbiProperty;
import org.jdbi.v3.core.generic.GenericType;
import org.jdbi.v3.core.mapper.immutables.JdbiImmutables;
import org.jdbi.v3.core.rule.H2DatabaseRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ImmutablesTest {
    @Rule
    public H2DatabaseRule dbRule = new H2DatabaseRule()
        .withConfig(JdbiImmutables.class, c -> c
            .registerImmutable(SubValue.class)
            .registerImmutable(FooBarBaz.class)
            .registerModifiable(FooBarBaz.class)
            .registerImmutable(Getter.class)
            .registerImmutable(Ignorable.class));

    private Jdbi jdbi;
    private Handle h;

    @Before
    public void setup() {
        jdbi = dbRule.getJdbi();
        h = dbRule.getSharedHandle();
        h.execute("create table immutables (t int, x varchar)");
    }

    // tag::example[]

    @Value.Immutable
    public interface Train {
        String name();
        int carriages();
        boolean observationCar();
    }

    @Test
    public void simpleTest() {
        jdbi.getConfig(JdbiImmutables.class).registerImmutable(Train.class);
        try (Handle handle = jdbi.open()) {
            handle.execute("create table train (name varchar, carriages int, observation_car boolean)");

            assertThat(
                handle.createUpdate("insert into train(name, carriages, observation_car) values (:name, :carriages, :observationCar)")
                    .bindPojo(ImmutableTrain.builder().name("Zephyr").carriages(8).observationCar(true).build())
                    .execute())
                .isEqualTo(1);

            assertThat(
                handle.createQuery("select * from train")
                    .mapTo(Train.class)
                    .findOnly())
                .extracting("name", "carriages", "observationCar")
                .containsExactly("Zephyr", 8, true);
        }
    }
    // end::example[]

    @Test
    public void parameterizedTest() {
        assertThat(
            h.createUpdate("insert into immutables(t, x) values (:t, :x)")
                .bindPojo(ImmutableSubValue.<String, Integer>builder().t(42).x("foo").build())
                .execute())
            .isEqualTo(1);

        assertThat(
            h.createQuery("select * from immutables")
                .mapTo(new GenericType<SubValue<String, Integer>>() {})
                .findOnly())
            .extracting("t", "x")
            .containsExactly(42, "foo");
    }

    public interface BaseValue<T> {
        T t();
    }

    @Value.Immutable
    public interface SubValue<X, T> extends BaseValue<T> {
        X x();
    }

    @Value.Immutable
    @Value.Modifiable
    public interface FooBarBaz {
        int id();
        Optional<String> foo();
        OptionalInt bar();
        OptionalDouble baz();
    }

    @Test
    public void testModifiable() {
        h.execute("create table fbb (id serial, foo varchar, bar int, baz real)");

        assertThat(h.createUpdate("insert into fbb (id, foo, bar, baz) values (:id, :foo, :bar, :baz)")
                .bindPojo(ModifiableFooBarBaz.create().setFoo("foo").setBar(42).setBaz(1.0))
                .execute())
            .isEqualTo(1);

        assertThat(h.createQuery("select * from fbb")
                .mapTo(ModifiableFooBarBaz.class)
                .findOnly())
            .extracting("id", "foo", "bar", "baz")
            .containsExactly(1, Optional.of("foo"), OptionalInt.of(42), OptionalDouble.of(1.0));

        assertThat(h.createQuery("select * from fbb")
                .mapTo(ImmutableFooBarBaz.class)
                .findOnly())
            .extracting("id", "foo", "bar", "baz")
        // FIXME: https://github.com/joel-costigliola/assertj-core/pull/1360
            .containsExactly(1, "foo", 42, 1.0);
    }

    @Value.Immutable
    @Value.Style(overshadowImplementation = true, get = {"is*", "get*"}, init = "set*")
    public interface Getter {
        int getFoo();
        boolean isBar();

        // Also test that we can also use overshadowed builders
        static Builder builder() {
            return new Builder();
        }
        class Builder extends ImmutableGetter.Builder {}
    }

    @Test
    public void testGetterStyle() {
        final Getter expected = Getter.builder().setFoo(42).setBar(true).build();
        h.execute("create table getter(foo int, bar boolean)");
        assertThat(h.createUpdate("insert into getter(foo, bar) values (:foo, :bar)")
                .bindPojo(expected)
                .execute())
            .isEqualTo(1);
        assertThat(h.createQuery("select * from getter")
                .mapTo(Getter.class)
                .findOnly())
            .isEqualTo(expected);
    }

    @Value.Immutable
    public interface Ignorable {
        @Value.Default
        default int foo() {
            return 1;
        }

        @Value.Check
        default void checkFoo() {
            if (foo() == 999) {
                throw new Boom();
            }
        }

        @Value.Derived
        @JdbiProperty(mappable = false)
        default int fooPlus40() {
            return foo() + 40;
        }
    }

    @Test
    public void testIgnoredProperties() {
        final Ignorable testy = ImmutableIgnorable.builder().foo(2).build();
        h.execute("create table ignorable (foo int, fooPlus40 int)");
        h.createUpdate("insert into ignorable(foo, fooPlus40) values (:foo, :fooPlus40)")
            .bindPojo(testy)
            .execute();
        assertThat(h.createQuery("select * from ignorable")
                .mapTo(Ignorable.class)
                .findOnly()
                .foo())
            .isEqualTo(testy.foo());

        assertThatThrownBy(() ->
                h.createQuery("select 999 as foo")
                    .mapTo(Ignorable.class)
                    .findOnly())
           .isInstanceOf(Boom.class);
    }

    static class Boom extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
