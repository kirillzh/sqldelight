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
package com.squareup.sqldelight.intellij.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.squareup.sqldelight.intellij.SqlDelightManager
import com.squareup.sqldelight.intellij.lang.SqliteFile
import com.squareup.sqldelight.intellij.util.doRename
import com.squareup.sqldelight.intellij.util.findUsages
import com.squareup.sqldelight.intellij.util.isDefinition
import com.squareup.sqldelight.intellij.util.leafAt
import com.squareup.sqldelight.old.resolution.Resolver
import com.squareup.sqldelight.old.validation.SqlDelightValidator

class SqlDelightElementRef(idNode: IdentifierElement, ruleName: String)
: PsiReferenceBase<IdentifierElement>(idNode, TextRange(0, ruleName.length)) {
  /**
   *  @see SqlDelightCompletionContributor
   */
  override fun getVariants() = emptyArray<Any>()

  override fun resolve(): PsiElement? {
    val manager = SqlDelightManager.getInstance(
        element.containingFile as? SqliteFile ?: return null) ?: return null
    var result: PsiElement? = null
    (element.containingFile as SqliteFile).parseThen({ parsed ->
      val ruleAtElement = parsed.leafAt(element.textOffset)
      if (ruleAtElement.isDefinition()) {
        result = element
        return@parseThen
      }
      val resolver = Resolver(manager.symbolTable, elementToFind = element.textOffset)
      SqlDelightValidator().validate(parsed.sql_stmt_list().sql_stmt().firstOrNull {
        it.start.startIndex < element.textOffset && it.stop.stopIndex > element.textOffset
      } ?: return@parseThen, resolver)
      if (resolver.elementFound.get() != null) {
        result = manager.getPsi(resolver.elementFound.get()!!)
      }
    })
    return result
  }

  override fun handleElementRename(newElementName: String): PsiElement {
    val file = myElement.containingFile

    val usageInfo = (myElement.parent as SqliteElement).findUsages(newElementName)
    (myElement.parent as SqliteElement).doRename(newElementName, usageInfo, file, null)

    return myElement
  }
}
