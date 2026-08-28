package com.masaibar.datastore.inspector.runtime.core

/**
 * Marks opt-in extension points that may change between minor releases without a deprecation
 * period.
 */
@MustBeDocumented
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This DataStore Inspector API is experimental and may change without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS
)
public annotation class ExperimentalDataStoreInspectorApi

/**
 * Marks public JVM declarations that are required by instrumentation, generated code, or
 * ServiceLoader and are not supported consumer APIs.
 */
@MustBeDocumented
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This DataStore Inspector API is internal and is not supported for consumer use."
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS
)
public annotation class InternalDataStoreInspectorApi
