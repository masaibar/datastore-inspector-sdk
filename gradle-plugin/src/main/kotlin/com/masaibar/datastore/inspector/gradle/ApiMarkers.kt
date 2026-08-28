package com.masaibar.datastore.inspector.gradle

/** Marks the Gradle DSL that follows the documented 1.x compatibility policy. */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS
)
public annotation class StableDataStoreInspectorGradleApi

/**
 * Marks Gradle DSL that may change between minor releases without a deprecation period.
 */
@MustBeDocumented
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This DataStore Inspector Gradle API is experimental and may change without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS
)
public annotation class ExperimentalDataStoreInspectorGradleApi

/**
 * Marks public Gradle implementation declarations that are not supported consumer APIs.
 */
@MustBeDocumented
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This DataStore Inspector Gradle API is internal and is not supported for consumer use."
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS
)
public annotation class InternalDataStoreInspectorGradleApi
