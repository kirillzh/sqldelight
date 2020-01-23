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
import com.squareup.sqldelight.old.resolution.resolve
import com.squareup.sqldelight.old.types.ArgumentType

internal class DeleteValidator(
    val resolver: Resolver,
    val scopedValues: List<Result> = emptyList()
) {
  fun validate(delete: SqliteParser.Delete_stmtContext) {
    val resolution = listOf(resolver.resolve(delete.table_name())).filterNotNull()

    var resolver: Resolver
    if (delete.with_clause() != null) {
      try {
        resolver = this.resolver.withResolver(delete.with_clause())
      } catch (e: SqlitePluginException) {
        resolver = this.resolver
        resolver.errors.add(ResolutionError.WithTableError(e.originatingElement, e.message))
      }
    } else {
      resolver = this.resolver
    }

    resolver = resolver.withScopedValues(scopedValues).withScopedValues(resolution)

    delete.expr()?.let { resolver.resolve(it, expectedType = ArgumentType.boolean(it)) }
  }
}
