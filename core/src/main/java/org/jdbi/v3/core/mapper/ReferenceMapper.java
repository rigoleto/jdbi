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

import java.sql.ResultSet;
import java.sql.SQLException;
import org.jdbi.v3.core.statement.StatementContext;

class ReferenceMapper<T> implements ColumnMapper<T> {
    private final ColumnGetter<T> getter;

    ReferenceMapper(ColumnGetter<T> getter) {
        this.getter = getter;
    }

    @Override
    public T map(ResultSet r, int i, StatementContext ctx) throws SQLException {
        T value = getter.get(r, i);
        return r.wasNull() ? null : value;
    }
}
