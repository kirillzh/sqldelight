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
import com.squareup.sqldelight.old.resolution.ResolutionError
import com.squareup.sqldelight.old.resolution.Resolver
import com.squareup.sqldelight.old.resolution.foreignKeys
import com.squareup.sqldelight.old.resolution.resolve
import com.squareup.sqldelight.old.types.ArgumentType
import com.squareup.sqldelight.old.types.ForeignKey

internal class CreateTableValidator(var resolver: Resolver) {
  fun validate(createTable: SqliteParser.Create_table_stmtContext) {
    val resolution = listOf(resolver.resolve(createTable))
    resolver = resolver.withScopedValues(resolution)

    (createTable.column_def().flatMap { it.column_constraint() }.map { it.expr() }
        + createTable.table_constraint().map { it.expr() })
        .filterNotNull()
        .forEach {
          resolver.resolve(it, false, ArgumentType.boolean(it))
        }

    createTable.table_constraint().forEach { tableConstraint ->
      if (tableConstraint.expr() != null) {
        resolver.resolve(tableConstraint.expr(), false, ArgumentType.boolean(tableConstraint.expr()))
      }
      (tableConstraint.indexed_column().map { it.column_name() } + tableConstraint.column_name())
          .forEach { resolver.resolve(resolution, it) }
    }

    createTable.column_def().forEach {
      if (it.column_constraint().filter { it.K_PRIMARY_KEY() != null }.size > 1) {
        resolver.errors.add(ResolutionError.CreateTableError(
            it, "Column can only have one primary key on a column"
        ))
      }
      if (it.column_constraint().filter { it.K_UNIQUE() != null }.size > 1) {
        resolver.errors.add(ResolutionError.CreateTableError(
            it, "Column can only have one unique constraint on a column"
        ))
      }
    }

    createTable.column_def()
        .flatMap { it.column_constraint() }
        .mapNotNull { it.foreign_key_clause() }
        .forEach {
          // The index can be supplied a few different ways:
          //   A. if no columns are supplied, the foreign index is the primary key constraints on the parent table.
          //   B. if the columns supplied have any unique constraint in them which follows the same
          //      collation as the table.

          if (it.column_name().size > 1) {
            resolver.errors.add(ResolutionError.CreateTableError(
                it, "Column can only reference a single foreign key"
            ))
            return@forEach
          }

          val foreignTablePrimaryKeys = resolver.foreignKeys(it.foreign_table())
          if (it.column_name().size == 0) {
            // Must map to the foreign tables primary key which must be exactly one column long.
            if (foreignTablePrimaryKeys.primaryKey.size != 1) {
              resolver.errors.add(ResolutionError.CreateTableError(
                  it, "Table ${it.foreign_table().text} has a composite primary key"
              ))
            }
          } else {
            val errors = resolver.errors.size
            it.column_name().forEach {
              resolver.resolve(
                  foreignTablePrimaryKeys.primaryKey
                      .plus(foreignTablePrimaryKeys.uniqueConstraints.flatMap { it })
                      .distinct(),
                  it,
                  errorText = "No column with unique constraint found with name ${it.text}"
              )
            }
            if (errors == resolver.errors.size &&
                !foreignTablePrimaryKeys.hasIndexWithColumns(it.column_name().map { it.text })) {
              resolver.errors.add(
                  ResolutionError.CreateTableError(it, "Table ${it.foreign_table().text} " +
                      "does not have a unique index on column ${it.column_name(0).text}"))
            }
          }
        }

    createTable.table_constraint().filter { it.foreign_key_clause() != null }.forEach { constraint ->
      val foreignClause = constraint.foreign_key_clause()
      val foreignTablePrimaryKeys = resolver.foreignKeys(foreignClause.foreign_table())

      if (foreignClause.column_name().size == 0) {
        // Must exact match foreign table primary key index.
        if (foreignTablePrimaryKeys.primaryKey.size != constraint.column_name().size) {
          resolver.errors.add(ResolutionError.CreateTableError(foreignClause, "Foreign key constraint" +
              " must match the primary key of the foreign table exactly. Constraint has " +
              "${constraint.column_name().size} columns and foreign table primary key has " +
              "${foreignTablePrimaryKeys.primaryKey.size} columns"))
        }
      } else if (!foreignTablePrimaryKeys.hasIndexWithColumns(
          foreignClause.column_name().map { it.text })) {
        resolver.errors.add(ResolutionError.CreateTableError(foreignClause, "Table" +
            " ${foreignClause.foreign_table().text} does not have a unique index on columns" +
            " ${foreignClause.column_name().map { it.text }}"))
      }
    }
  }

  private fun ForeignKey.hasIndexWithColumns(columns: List<String>) =
      (uniqueConstraints + listOf(primaryKey)).any {
        columns.size == it.size && columns.containsAll(it.map { it.name })
      }
}
