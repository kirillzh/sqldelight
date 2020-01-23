package com.squareup.sqldelight.old.util

import com.squareup.javapoet.NameAllocator

internal fun NameAllocator.getOrSet(objRef: Any, name: String): String {
  try {
    return get(objRef)
  } catch (e: IllegalArgumentException) {
    return newName(name, objRef)
  }
}