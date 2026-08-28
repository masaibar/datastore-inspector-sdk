package com.masaibar.datastore.inspector.protocol

/**
 * Marks Kotlin declarations that exist to implement the versioned wire contract and are not a
 * supported consumer API.
 */
@MustBeDocumented
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "This DataStore Inspector Protocol API is internal and is not supported for consumer use."
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS
)
public annotation class InternalDataStoreInspectorProtocolApi
