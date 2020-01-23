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
import com.squareup.sqldelight.old.Status
import com.squareup.sqldelight.old.model.SqlStmt
import com.squareup.sqldelight.old.model.columnName
import com.squareup.sqldelight.old.model.javadocText
import com.squareup.sqldelight.old.model.pathAsType
import com.squareup.sqldelight.old.resolution.ResolutionError
import com.squareup.sqldelight.old.resolution.Resolver
import com.squareup.sqldelight.old.resolution.query.QueryResults
import com.squareup.sqldelight.old.resolution.query.resultColumnSize
import com.squareup.sqldelight.old.resolution.resolve
import com.squareup.sqldelight.old.types.SymbolTable
import org.antlr.v4.runtime.ParserRuleContext
import java.io.File
import java.util.ArrayList
import java.util.LinkedHashSet

class SqlDelightValidator {
  fun validate(
      relativePath: String,
      parse: SqliteParser.ParseContext,
      symbolTable: SymbolTable
  ): Status.ValidationStatus {
    val errors = ArrayList<ResolutionError>()
    val queries = ArrayList<QueryResults>()
    val views = ArrayList<QueryResults>()
    val dependencies = LinkedHashSet<Any>()
    val sqlStmts = ArrayList<SqlStmt>()

    val columnNames = linkedSetOf<String>()
    val sqlStatementNames = linkedSetOf<String>()

    if (relativePath.substringBeforeLast(File.separatorChar).contains('.')) {
      val folder = relativePath.substringBeforeLast(File.separatorChar)
      val file = relativePath.substringAfterLast(File.separatorChar)
      errors.add(ResolutionError.IncompleteRule(parse, ".sq file parent directory should be" +
          " package-compatible and not contain dots. Use " +
          "${folder.replace('.', File.separatorChar)}${File.separatorChar}$file instead of " +
          "$relativePath"))
    }

    parse.sql_stmt_list().create_table_stmt()?.let { createTable ->
      val resolver = Resolver(symbolTable)
      CreateTableValidator(resolver).validate(createTable)

      createTable.column_def().forEach { column ->
        if (!columnNames.add(column.column_name().text.columnName())) {
          errors.add(ResolutionError.CreateTableError(
              column.column_name(), "Duplicate column name"
          ))
        }
      }
      errors.addAll(resolver.errors)
      dependencies.addAll(resolver.dependencies)
      sqlStmts.add(SqlStmt(resolver.arguments, createTable))
    }

    parse.sql_stmt_list().sql_stmt().forEach { sqlStmt ->
      val resolver = Resolver(symbolTable)
      if (columnNames.contains(sqlStmt.sql_stmt_name().text)) {
        errors.add(ResolutionError.CollisionError(
            sqlStmt.sql_stmt_name(), "SQL identifier collides with column name"
        ))
      }
      if (!sqlStatementNames.add(sqlStmt.sql_stmt_name().text)) {
        errors.add(ResolutionError.CollisionError(
            sqlStmt.sql_stmt_name(), "Duplicate SQL identifier"
        ))
      }
      if (sqlStmt.select_stmt() != null) {
        val errorsBeforeResolution = resolver.errors.size
        val resolution = resolver.resolve(sqlStmt.select_stmt())
        if (resolver.errors.size == errorsBeforeResolution) {
          if (resolution.resultColumnSize() == 0) {
            errors.add(ResolutionError.ExpressionError(sqlStmt.select_stmt(),
                "No result column found"))
          } else {
            queries.add(QueryResults(
                sqlStmt.sql_stmt_name(),
                resolution,
                relativePath.pathAsType(),
                javadoc = sqlStmt.javadocText()
            ).modifyDuplicates())
          }
        }
      } else if (sqlStmt.create_view_stmt() != null) {
        val errorsBeforeResolution = resolver.errors.size
        val resolution = resolver.resolve(sqlStmt.create_view_stmt().view_name())
        if (resolver.errors.size == errorsBeforeResolution) {
          views.add(resolution.modifyDuplicates())
        }
      } else {
        validate(sqlStmt, resolver)
      }
      errors.addAll(resolver.errors)
      dependencies.addAll(resolver.dependencies)
      sqlStmts.add(SqlStmt(resolver.arguments, sqlStmt, tableNames(sqlStmt, resolver)))
    }

    val importTypes = linkedSetOf<String>()
    parse.sql_stmt_list().import_stmt().forEach { import ->
      if (!importTypes.add(import.java_type_name().text.substringAfterLast('.'))) {
        errors.add(ResolutionError.CollisionError(
            import.java_type_name(),
            "Multiple imports for type ${import.java_type_name().text.substringAfterLast('.')}"
        ))
      }
    }

    return if (errors.isEmpty())
      Status.ValidationStatus.Validated(parse, dependencies, queries, views, sqlStmts)
    else
      Status.ValidationStatus.Invalid((errors)
          .distinctBy {
            it.originatingElement.start.startIndex to it.originatingElement.stop.stopIndex to it.errorMessage
          }, dependencies)
  }

  fun tableNames(sqlStmt: SqliteParser.Sql_stmtContext, resolver: Resolver):Set<String> =
    sqlStmt.run {
      select_stmt()?.run { return resolver.tableDependencies }
      insert_stmt()?.run { return setOf(table_name().text) }
      update_stmt()?.run { return setOf(table_name().text) }
      delete_stmt()?.run { return setOf(table_name().text) }
      create_index_stmt()?.run { return setOf(table_name().text) }
      create_trigger_stmt()?.run { return setOf(table_name().text) }
      create_view_stmt()?.run { return resolver.tableDependencies }
      return emptySet()
    }

  fun validate(rule: ParserRuleContext, resolver: Resolver) {
    when (rule) {
      is SqliteParser.Create_table_stmtContext -> CreateTableValidator(resolver).validate(rule)
      is SqliteParser.Sql_stmtContext -> validate(rule, resolver)
    }
  }

  fun validate(sqlStmt: SqliteParser.Sql_stmtContext, resolver: Resolver) {
    sqlStmt.run {
      select_stmt()?.apply { resolver.resolve(this) }
      insert_stmt()?.apply { InsertValidator(resolver).validate(this) }
      update_stmt()?.apply { UpdateValidator(resolver).validate(this) }
      delete_stmt()?.apply { DeleteValidator(resolver).validate(this) }
      create_index_stmt()?.apply { CreateIndexValidator(resolver).validate(this) }
      create_trigger_stmt()?.apply { CreateTriggerValidator(resolver).validate(this) }
      create_view_stmt()?.apply { resolver.resolve(select_stmt()) }
    }
  }

  companion object {
    const val ALL_FILE_DEPENDENCY = "all_file_dependency"
  }
}
