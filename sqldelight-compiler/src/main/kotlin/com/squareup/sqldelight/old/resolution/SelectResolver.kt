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
package com.squareup.sqldelight.old.resolution

import com.squareup.sqldelight.SqliteParser
import com.squareup.sqldelight.old.SqlitePluginException
import com.squareup.sqldelight.old.resolution.query.QueryResults
import com.squareup.sqldelight.old.resolution.query.Result
import com.squareup.sqldelight.old.resolution.query.Table
import com.squareup.sqldelight.old.resolution.query.Value
import com.squareup.sqldelight.old.resolution.query.merge
import com.squareup.sqldelight.old.resolution.query.resultColumnSize
import com.squareup.sqldelight.old.types.ArgumentType.SingleValue
import com.squareup.sqldelight.old.validation.SelectOrValuesValidator
import com.squareup.sqldelight.old.validation.SelectStmtValidator

internal fun Resolver.resolve(
    selectStmt: SqliteParser.Select_stmtContext,
    recursiveCommonTable: SqliteParser.Common_table_expressionContext? = null
): List<Result> {
  val resolver = if (selectStmt.with_clause() != null) {
    try {
      withResolver(selectStmt.with_clause())
    } catch (e: SqlitePluginException) {
      errors.add(ResolutionError.WithTableError(e.originatingElement, e.message))
      this
    }
  } else {
    this
  }

  var resolution = resolver.resolve(selectStmt.select_or_values(0), selectStmt)

  // Resolve other compound select statements and verify they have equivalent columns.
  selectStmt.select_or_values().drop(1).forEach {
    val compoundValues: List<Result>
    if (it == selectStmt.select_or_values().last() && recursiveCommonTable != null) {
      // The last compound select statement is permitted usage of the recursed common table.
      compoundValues = resolver.resolve(
          it,
          recursiveCommonTable = recursiveCommonTable.table_name() to
              commonTableResolution(recursiveCommonTable, resolution)
      )
    } else {
      compoundValues = resolver.resolve(it)
    }
    if (compoundValues.resultColumnSize() != resolution.resultColumnSize()) {
      errors.add(ResolutionError.CompoundError(it,
          "Unexpected number of columns in compound statement found: " +
              "${compoundValues.resultColumnSize()} expected: ${resolution.resultColumnSize()}"))
    }

    resolution = resolution.merge(compoundValues)
  }

  return resolution
}

/**
 * Takes a select_or_values rule and returns the columns selected.
 */
internal fun Resolver.resolve(
    selectOrValues: SqliteParser.Select_or_valuesContext,
    parentSelect: SqliteParser.Select_stmtContext? = null,
    recursiveCommonTable: Pair<SqliteParser.Table_nameContext, List<Result>>? = null
): List<Result> {
  val resolution: List<Result>
  if (selectOrValues.K_VALUES() != null) {
    // No columns are available, only selected columns are returned.
    SelectOrValuesValidator(this).validate(selectOrValues)
    return resolve(selectOrValues.values())
  } else if (selectOrValues.join_clause() != null) {
    resolution = resolve(selectOrValues.join_clause(), recursiveCommonTable)
  } else if (selectOrValues.table_or_subquery().size > 0) {
    resolution = selectOrValues.table_or_subquery().flatMap { resolve(it, recursiveCommonTable) }
  } else {
    resolution = emptyList()
  }

  // Validate the select or values has valid expressions before aliasing/selection.
  SelectOrValuesValidator(withScopedValues(resolution)).validate(selectOrValues)

  if (parentSelect != null) {
    SelectStmtValidator(withScopedValues(resolution)).validate(parentSelect)
  }

  return selectOrValues.result_column().flatMap { resolve(it, resolution) }
}

/**
 * Take in a list of available columns and return a list of selected columns.
 */
internal fun Resolver.resolve(
    resultColumn: SqliteParser.Result_columnContext,
    availableValues: List<Result>
): List<Result> {
  if (resultColumn.text.equals("*")) {
    // SELECT *
    return availableValues
  }
  if (resultColumn.table_name() != null) {
    // SELECT some_table.*
    val tables = availableValues.filter { it is Table || it is QueryResults }
    val result =  tables.filter { it.name == resultColumn.table_name().text }
    if (result.resultColumnSize() == 0) {
      errors.add(ResolutionError.TableNameNotFound(
          resultColumn.table_name(),
          "Table name ${resultColumn.table_name().text} not found",
          tables.map { it.name }.distinct()))
      return emptyList()
    }
    findElementAtCursor(resultColumn.table_name(), tables.first().element, elementToFind)
    return result
  }
  if (resultColumn.expr() != null) {
    // SELECT expr
    var response = copy(scopedValues = listOf(availableValues)).resolve(resultColumn.expr()) ?: return emptyList()
    if (resultColumn.column_alias() != null) {
      response = response.copy(
          name = resultColumn.column_alias().text,
          element = resultColumn.column_alias()
      )
    }
    return listOf(response)
  }

  errors.add(ResolutionError.IncompleteRule(resultColumn, "Result set requires at least one column"))
  return emptyList()
}


/**
 * Takes a value rule and returns the columns introduced. Validates that any
 * appended values have the same length. Optionally accepts a list of expected value types that can
 * be used to populate bind parameters.
 */
internal fun Resolver.resolve(
    values: SqliteParser.ValuesContext,
    expectedValues: List<Value?> = emptyList()
): List<Result> {
  val selected = values.expr().mapIndexed { i, expr ->
    if (expectedValues.size > i) resolve(expr, expectedType = SingleValue(expectedValues[i]))
    else resolve(expr)
  }.filterNotNull()

  if (values.values() != null) {
    val joinedValues = resolve(values.values(), expectedValues)
    if (joinedValues.size != selected.size) {
      errors.add(ResolutionError.ValuesError(values.values(), "Unexpected number of columns in" +
          " values found: ${joinedValues.size} expected: ${selected.size}"))
    }
    // TODO: Type check
  }
  return selected
}
