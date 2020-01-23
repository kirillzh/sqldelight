/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sqldelight.old.validation

import com.squareup.sqldelight.SqliteParser
import com.squareup.sqldelight.old.SqlitePluginException
import com.squareup.sqldelight.old.resolution.ResolutionError
import com.squareup.sqldelight.old.resolution.Resolver
import com.squareup.sqldelight.old.resolution.query.Result
import com.squareup.sqldelight.old.resolution.query.Value
import com.squareup.sqldelight.old.resolution.query.resultColumnSize
import com.squareup.sqldelight.old.resolution.resolve

internal class InsertValidator(
    var resolver: Resolver,
    val scopedValues: List<Result> = emptyList()
) {
  fun validate(insert: SqliteParser.Insert_stmtContext) {
    val resolution = listOf(resolver.resolve(insert.table_name())).filterNotNull()
    val expectedTypes: List<Value?>
    if (insert.column_name().isNotEmpty()) {
      expectedTypes = insert.column_name().map { resolver.resolve(resolution, it) as? Value }
    } else if (insert.K_DEFAULT() != null) {
      expectedTypes = emptyList()
    } else {
      expectedTypes = resolution.flatMap { it.expand() }
    }

    // Verify that the required columns are included.
    resolution.flatMap { it.expand() }
        .filter { !it.nullable && !it.hasDefaultValue }
        .filter { requiredColumn -> expectedTypes.none { it == requiredColumn } }
        .apply {
          if (size == 1) {
            resolver.errors.add(ResolutionError.InsertError(
                insert, "Cannot populate default value for column ${first().name}, it must be" +
                " specified in insert statement.")
            )
          } else if (size > 1) {
            resolver.errors.add(ResolutionError.InsertError(
                insert, "Cannot populate default values for columns" +
                " (${map { it.name }.joinToString()}), they must be specified in insert statement.")
            )
          }
        }

    if (insert.with_clause() != null) {
      try {
        resolver = resolver.withResolver(insert.with_clause())
      } catch (e: SqlitePluginException) {
        resolver.errors.add(ResolutionError.WithTableError(e.originatingElement, e.message))
      }
    }

    val errorsBefore = resolver.errors.size
    val valuesBeingInserted: List<Result>
    if (insert.values() != null) {
      if (insert.values().expr().size != expectedTypes.size) {
        resolver.errors.add(ResolutionError.InsertError(
            insert.select_stmt() ?: insert.values() ?: insert, "Unexpected number of values being" +
            " inserted. found: ${insert.values().expr().size} expected: ${expectedTypes.size}"
        ))
      }
      valuesBeingInserted = resolver.withScopedValues(scopedValues).resolve(insert.values(), expectedTypes)
    } else if (insert.select_stmt() != null) {
      valuesBeingInserted = resolver.resolve(insert.select_stmt())
    } else {
      valuesBeingInserted = emptyList()
    }

    if (insert.K_DEFAULT() != null) {
      // Inserting default values, no need to check against column size.
      return
    }

    val columnSize = if (insert.column_name().size > 0) insert.column_name().size else resolution.resultColumnSize()
    if (errorsBefore == resolver.errors.size && valuesBeingInserted.resultColumnSize() != columnSize) {
      resolver.errors.add(ResolutionError.InsertError(
          insert.select_stmt() ?: insert.values() ?: insert, "Unexpected number of " +
          "values being inserted. found: ${valuesBeingInserted.resultColumnSize()} expected: $columnSize"
      ))
    }
  }
}
