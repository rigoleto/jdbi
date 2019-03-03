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
package org.jdbi.v3.core.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jdbi.v3.meta.Beta;

/**
 * Abstract JdbiConfig implementation that holds a Map cache.
 * Note that unlike most JdbiConfig types, this cache is Jdbi level
 * and shared, so it should not hold data that expects re-configuration after
 * first use to work.
 * @param <This> the concrete cache implementation type
 * @param <K> map key type
 * @param <V> map value type
 */
@Beta
public abstract class JdbiCache<This extends JdbiCache<This, K, V>, K, V> implements JdbiConfig<This> {
    private final Map<K, V> cache = createMap();

    protected Map<K, V> createMap() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Does not actually create a copy!!
     */
    @SuppressWarnings("unchecked")
    @Override
    public This createCopy() {
        return (This) this;
    }

    public Map<K, V> getCache() {
        return cache;
    }
}
